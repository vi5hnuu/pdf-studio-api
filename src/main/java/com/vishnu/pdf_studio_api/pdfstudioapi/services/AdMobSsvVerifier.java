package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Verifies AdMob rewarded-ad server-side verification (SSV) callbacks.
 *
 * <p>The rewarded-credit endpoint used to grant on the client's word alone: any authenticated
 * caller who sent an idempotency key was credited, so a modified app — or on the web, simply
 * pressing the button — minted credits up to the daily cap without an ad ever playing.
 *
 * <p>SSV closes that. AdMob calls this service directly when an ad genuinely completes, signing
 * the query string with one of Google's rotating EC keys. Only a request whose signature checks
 * out against the key named by {@code key_id} results in a grant, so the credit is tied to an
 * impression Google confirms rather than to a client's claim.
 *
 * @see <a href="https://developers.google.com/admob/android/ssv">AdMob SSV</a>
 */
@Component
@Slf4j
public class AdMobSsvVerifier {

    /** Google's published verifier keys. Rotated, so the cache is refreshed on an unknown id. */
    private static final String DEFAULT_KEY_URL =
            "https://gstatic.com/admob/reward/verifier-keys.json";

    /** Callbacks older than this are rejected, so a captured URL cannot be replayed later. */
    private static final Duration MAX_AGE = Duration.ofMinutes(10);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String keyUrl;
    private final boolean required;

    /** keyId → public key. Refreshed when a callback names a key we do not hold. */
    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile Instant keysFetchedAt = Instant.EPOCH;

    public AdMobSsvVerifier(
            @Value("${app.admob.ssv.key-url:" + DEFAULT_KEY_URL + "}") String keyUrl,
            @Value("${app.admob.ssv.required:true}") boolean required) {
        this.keyUrl = keyUrl;
        this.required = required;
    }

    /**
     * Whether a callback must verify before credits are granted.
     *
     * <p>Only ever false in local development, where Google cannot reach the callback URL.
     */
    public boolean required() {
        return required;
    }

    /**
     * Checks one callback.
     *
     * @param query the raw query string exactly as received, signature included
     * @return true when the signature is valid and the callback is recent
     */
    public boolean verify(String query) {
        if (query == null || query.isBlank()) return false;

        // Google signs everything before "&signature=", so the bytes must be taken from the raw
        // query rather than from re-encoded parameters — re-encoding would change them.
        int signatureAt = query.indexOf("&signature=");
        if (signatureAt < 0) {
            log.warn("AdMob SSV callback has no signature parameter.");
            return false;
        }
        String signedContent = query.substring(0, signatureAt);

        Map<String, String> params = parse(query);
        String keyId = params.get("key_id");
        String signature = params.get("signature");
        if (keyId == null || signature == null) {
            log.warn("AdMob SSV callback is missing key_id or signature.");
            return false;
        }

        if (!isRecent(params.get("timestamp"))) return false;

        PublicKey key = keyFor(keyId);
        if (key == null) {
            log.warn("AdMob SSV callback names unknown key_id {}.", keyId);
            return false;
        }

        try {
            Signature ecdsa = Signature.getInstance("SHA256withECDSA");
            ecdsa.initVerify(key);
            ecdsa.update(signedContent.getBytes(StandardCharsets.UTF_8));
            return ecdsa.verify(Base64.getUrlDecoder().decode(signature));
        } catch (Exception e) {
            log.warn("AdMob SSV signature check failed: {}", e.toString());
            return false;
        }
    }

    /** Splits a raw query string without decoding, since the signed bytes are the raw ones. */
    public static Map<String, String> parse(String query) {
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return params;
    }

    /** AdMob sends epoch milliseconds; a stale callback is a replay of a captured URL. */
    private boolean isRecent(String timestamp) {
        if (timestamp == null) return false;
        try {
            Instant sent = Instant.ofEpochMilli(Long.parseLong(timestamp));
            Duration age = Duration.between(sent, Instant.now()).abs();
            if (age.compareTo(MAX_AGE) > 0) {
                log.warn("AdMob SSV callback is {} old; rejecting.", age);
                return false;
            }
            return true;
        } catch (NumberFormatException e) {
            log.warn("AdMob SSV callback has an unparseable timestamp: {}", timestamp);
            return false;
        }
    }

    private PublicKey keyFor(String keyId) {
        PublicKey known = keys.get(keyId);
        if (known != null) return known;

        // Unknown id means Google rotated. Refresh at most once a minute so a bogus id in a
        // forged callback cannot be used to hammer Google's endpoint.
        if (Duration.between(keysFetchedAt, Instant.now()).toMinutes() < 1) return null;
        refreshKeys();
        return keys.get(keyId);
    }

    private synchronized void refreshKeys() {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(keyUrl)).GET()
                            .timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Could not fetch AdMob verifier keys: HTTP {}", response.statusCode());
                return;
            }

            Map<String, PublicKey> parsed = new HashMap<>();
            KeyFactory ecKeys = KeyFactory.getInstance("EC");
            for (JsonNode node : MAPPER.readTree(response.body()).path("keys")) {
                String id = node.path("keyId").asText(null);
                String base64 = node.path("base64").asText(null);
                if (id == null || base64 == null) continue;
                parsed.put(id, ecKeys.generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(base64))));
            }

            if (!parsed.isEmpty()) {
                keys = Map.copyOf(parsed);
                log.info("Loaded {} AdMob verifier keys.", parsed.size());
            }
        } catch (Exception e) {
            log.warn("Could not refresh AdMob verifier keys: {}", e.toString());
        } finally {
            keysFetchedAt = Instant.now();
        }
    }
}

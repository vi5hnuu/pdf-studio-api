package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Verifies the Google-issued OIDC token that Cloud Pub/Sub places in the
 * {@code Authorization: Bearer …} header of an authenticated push request, so the RTDN
 * webhook can trust a call actually came from our Pub/Sub subscription — not just from
 * anyone who learned the shared secret.
 *
 * <p>Two things are checked:
 * <ol>
 *   <li><b>Audience</b> — the value configured on the push subscription's authentication
 *       settings ({@code app.play.webhook.audience}, typically the webhook URL). This is
 *       the most common misconfiguration to check first.</li>
 *   <li><b>Signer email</b> (optional) — the service account Pub/Sub signs push tokens
 *       with ({@code app.play.webhook.service-account}), confirming the request came from
 *       our project.</li>
 * </ol>
 *
 * <p>Enabled only when an audience is configured; otherwise {@link #enabled()} is false
 * and the webhook falls back to the shared-secret guard.
 */
@Component
@Slf4j
public class PubSubJwtVerifier {

    private static final NetHttpTransport TRANSPORT = new NetHttpTransport();
    private static final GsonFactory GSON_FACTORY = new GsonFactory();

    private final String expectedAudience;
    private final String expectedServiceAccount;

    public PubSubJwtVerifier(
            @Value("${app.play.webhook.audience:}") String expectedAudience,
            @Value("${app.play.webhook.service-account:}") String expectedServiceAccount) {
        this.expectedAudience = expectedAudience;
        this.expectedServiceAccount = expectedServiceAccount;
    }

    /** Whether OIDC verification is configured (an audience is set). */
    public boolean enabled() {
        return expectedAudience != null && !expectedAudience.isBlank();
    }

    /**
     * Verifies the {@code Authorization} header of a Pub/Sub push request.
     * @return true if the bearer JWT is Google-signed, has the configured audience, and
     *         (when configured) was signed by the expected service account.
     */
    public boolean verify(String authorizationHeader) {
        if (!enabled()) return false;
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            log.warn("Pub/Sub push: missing or malformed Authorization header");
            return false;
        }
        final String token = authorizationHeader.substring("Bearer ".length()).trim();
        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(TRANSPORT, GSON_FACTORY)
                    .setAudience(Collections.singletonList(expectedAudience))
                    .build();
            GoogleIdToken idToken = verifier.verify(token);
            if (idToken == null) {
                log.warn("Pub/Sub push: token signature/audience/expiry did not validate");
                return false;
            }
            if (expectedServiceAccount != null && !expectedServiceAccount.isBlank()) {
                String email = idToken.getPayload().getEmail();
                if (!expectedServiceAccount.equalsIgnoreCase(email)) {
                    log.warn("Pub/Sub push: signer '{}' does not match expected '{}'",
                            email, expectedServiceAccount);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("Pub/Sub push: token verification failed: {}", e.getMessage());
            return false;
        }
    }
}

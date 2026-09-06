package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.CreditsService;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.PubSubJwtVerifier;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Receives Google Play Real-time Developer Notifications (Pub/Sub push) to claw back
 * credits when a one-time purchase is refunded/voided.
 *
 * <p>Machine-to-machine (no user JWT). Authenticated by, in order of strength:
 * <ol>
 *   <li>the <b>Pub/Sub OIDC token</b> in the {@code Authorization} header (verified via
 *       {@link PubSubJwtVerifier}) when an audience is configured; and/or</li>
 *   <li>a <b>shared secret</b> in the query string.</li>
 * </ol>
 * Every configured mechanism must pass; if none is configured the endpoint is closed.
 * Always acks 200 on authorized deliveries so Pub/Sub doesn't retry; unknown notification
 * types are ignored.
 */
@RestController
@RequestMapping("/api/v1/credits")
@RequiredArgsConstructor
@Slf4j
public class BillingWebhookController {

    private final CreditsService creditsService;
    private final ObjectMapper objectMapper;
    private final PubSubJwtVerifier pubSubJwtVerifier;

    @Value("${app.play.rtdn-secret:}")
    private String rtdnSecret;

    @PostMapping("/play-rtdn")
    public ResponseEntity<Void> handle(@RequestParam(value = "secret", required = false) String secret,
                                       @RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        if (!isAuthorized(secret, request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        final JsonNode notification;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) body.get("message");
            if (message == null) return ResponseEntity.ok().build();
            String data = (String) message.get("data");
            if (data == null) return ResponseEntity.ok().build();
            notification = objectMapper.readTree(
                    new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8));
        } catch (Exception malformed) {
            // A payload we cannot parse will never parse — acking stops Pub/Sub retrying forever.
            log.error("Unparseable Play RTDN, acking to stop redelivery: {}", malformed.getMessage());
            return ResponseEntity.ok().build();
        }

        try {
            JsonNode voided = notification.get("voidedPurchaseNotification");
            if (voided != null && voided.hasNonNull("purchaseToken")) {
                creditsService.revokeCreditsForToken(voided.get("purchaseToken").asText());
                return ResponseEntity.ok().build();
            }

            // A purchase that completed while the app was killed is never redeemed by the client,
            // so the server credits it here rather than leaving the user paid-but-uncredited.
            JsonNode oneTime = notification.get("oneTimeProductNotification");
            if (oneTime != null && oneTime.hasNonNull("purchaseToken") && oneTime.hasNonNull("sku")) {
                creditsService.reconcilePurchase(
                        oneTime.get("purchaseToken").asText(), oneTime.get("sku").asText());
            }
            // testNotification and anything unrecognised are intentionally ignored.
            return ResponseEntity.ok().build();

        } catch (Exception processingFailure) {
            // A transient failure here (database down mid-clawback) must NOT be acked: acking would
            // discard the notification permanently and leave revoked credits spendable. Returning
            // 5xx makes Pub/Sub redeliver, and the handlers are idempotent.
            log.error("Failed to process Play RTDN, returning 500 for redelivery", processingFailure);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Authorizes the push using every configured mechanism (OIDC token and/or shared
     * secret). Each configured check must pass; if none is configured the call is rejected
     * (fail closed) so the endpoint is never left open by accident.
     */
    private boolean isAuthorized(String secret, HttpServletRequest request) {
        final boolean jwtConfigured = pubSubJwtVerifier.enabled();
        final boolean secretConfigured = rtdnSecret != null && !rtdnSecret.isBlank();

        if (!jwtConfigured && !secretConfigured) {
            log.warn("RTDN webhook called but neither OIDC audience nor shared secret is configured — rejecting.");
            return false;
        }
        if (jwtConfigured && !pubSubJwtVerifier.verify(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            return false;
        }
        if (secretConfigured && !rtdnSecret.equals(secret)) {
            return false;
        }
        return true;
    }
}

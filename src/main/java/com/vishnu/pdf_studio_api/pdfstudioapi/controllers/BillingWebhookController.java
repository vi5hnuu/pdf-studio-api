package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.CreditsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.Map;

/**
 * Receives Google Play Real-time Developer Notifications (Pub/Sub push) to claw back
 * credits when a one-time purchase is refunded/voided.
 *
 * <p>Public (no JWT — it's machine-to-machine) but guarded by a shared secret in the query
 * string. Always acks 200 on well-formed, authorized deliveries so Pub/Sub doesn't retry;
 * unknown notification types are simply ignored.
 */
@RestController
@RequestMapping("/api/v1/credits")
@RequiredArgsConstructor
@Slf4j
public class BillingWebhookController {

    private final CreditsService creditsService;
    private final ObjectMapper objectMapper;

    @Value("${app.play.rtdn-secret:}")
    private String rtdnSecret;

    @PostMapping("/play-rtdn")
    public ResponseEntity<Void> handle(@RequestParam(value = "secret", required = false) String secret,
                                       @RequestBody Map<String, Object> body) {
        if (rtdnSecret == null || rtdnSecret.isBlank() || !rtdnSecret.equals(secret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) body.get("message");
            if (message == null) return ResponseEntity.ok().build();
            String data = (String) message.get("data");
            if (data == null) return ResponseEntity.ok().build();

            JsonNode notification = objectMapper.readTree(new String(Base64.getDecoder().decode(data)));
            JsonNode voided = notification.get("voidedPurchaseNotification");
            if (voided != null && voided.hasNonNull("purchaseToken")) {
                creditsService.revokeCreditsForToken(voided.get("purchaseToken").asText());
            }
        } catch (Exception e) {
            // Never fail the webhook on a parse issue — log and ack to avoid redelivery storms.
            log.error("Failed to process Play RTDN: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}

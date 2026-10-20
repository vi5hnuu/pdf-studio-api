package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.RazorpayProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.security.CurrentUser;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.RazorpayService;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.RazorpaySignatures;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * Razorpay credit purchase endpoints for the web tier.
 *
 * <p>The order and verify paths are JWT-authenticated and bind every grant to the account the
 * token names. The webhook is a machine-to-machine delivery authenticated by HMAC on the raw body
 * — it does not share the JWT chain, which is why the path is listed on
 * {@code webhookFilterChain} in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/credits/razorpay")
@RequiredArgsConstructor
@Slf4j
public class RazorpayController {

    private final RazorpayService razorpayService;
    private final RazorpayProperties properties;
    private final ObjectMapper mapper;

    @PostMapping("/order")
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody Map<String, String> body) {
        String productId = body.get("productId");
        var draft = razorpayService.createOrder(CurrentUser.requireId(), productId);
        return ResponseEntity.ok(Map.of("success", true, "data", draft.toMap()));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        var outcome = razorpayService.verifyAndGrant(
                CurrentUser.requireId(),
                body.get("razorpay_order_id"),
                body.get("razorpay_payment_id"),
                body.get("razorpay_signature"),
                ClientIp.of(request));
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", outcome.alreadyGranted()
                        ? "This payment has already been applied to your account."
                        : "Credits added.",
                "data", outcome.toMap()));
    }

    /**
     * Pub-style webhook from Razorpay. The raw body is HMAC-signed with the webhook secret;
     * parsing to JSON before verification would silently rewrite whitespace and break the match,
     * so the body is read as a byte stream, verified, THEN parsed.
     *
     * <p>Always 2xx on parse/content issues after signature verification: Razorpay retries on
     * any non-2xx and a payload we cannot handle should not reserve retries forever. The audit
     * trail lives in the log. A failed signature IS 4xx, so a forged request earns no retry.
     */
    /** 64 KB is well above the largest Razorpay event body (~8-12 KB) and well below OOM territory. */
    private static final int MAX_WEBHOOK_BODY_BYTES = 64 * 1024;

    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(HttpServletRequest request,
                                          @RequestHeader(value = "X-Razorpay-Signature", required = false)
                                          String signature) throws IOException {
        if (!properties.isWebhookEnabled()) {
            // Not configured — a webhook we cannot authenticate must not be acted on, and
            // returning 503 tells Razorpay this instance is not currently a valid target.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("razorpay disabled");
        }
        if (signature == null || signature.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("missing signature");
        }

        // Bound the read: a signature-less probe could otherwise send a 100 MB body and let the
        // server allocate all of it before any check runs. Read up to the cap plus one byte; if
        // the stream still has more, refuse — a legitimate Razorpay body is far smaller.
        byte[] rawBody = request.getInputStream().readNBytes(MAX_WEBHOOK_BODY_BYTES + 1);
        if (rawBody.length > MAX_WEBHOOK_BODY_BYTES) {
            log.warn("Razorpay webhook rejected: body exceeds {} bytes.", MAX_WEBHOOK_BODY_BYTES);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("body too large");
        }
        if (!RazorpaySignatures.verifyWebhook(rawBody, signature, properties.getWebhookSecret())) {
            log.warn("Razorpay webhook rejected: signature mismatch (bodyLen={}).", rawBody.length);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("bad signature");
        }

        try {
            JsonNode event = mapper.readTree(rawBody);
            String type = event.path("event").asText("<unknown>");
            log.info("Razorpay webhook accepted: event={} id={}", type,
                    request.getHeader("X-Razorpay-Event-Id"));
            razorpayService.handleWebhook(event);
        } catch (Exception parseOrHandle) {
            // At this point the signature was valid — Razorpay really did send this. A parse
            // failure means an unexpected shape we should log and move on; redelivering the same
            // bad event forever is not useful.
            log.error("Razorpay webhook could not be processed after signature verification", parseOrHandle);
        }
        return ResponseEntity.ok("ok");
    }
}

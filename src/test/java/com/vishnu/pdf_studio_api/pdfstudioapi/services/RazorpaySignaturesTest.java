package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The HMAC paths every Razorpay request runs through. These two signatures protect every rupee
 * on the gateway so each is unit-tested here, not left to an integration test — one wrong
 * compare is one person's credits going to another account.
 */
class RazorpaySignaturesTest {

    private static final String KEY_SECRET = "api_secret_rzp_test_abc";
    private static final String WEBHOOK_SECRET = "webhook_secret_xyz";

    @Test
    @DisplayName("payment signature: accepts a valid order|payment signature")
    void paymentSignatureAccepted() {
        String orderId = "order_ABCDEFGHIJ";
        String paymentId = "pay_ZYXWVUTSRQ";
        String signature = hmacHex((orderId + "|" + paymentId), KEY_SECRET);

        assertTrue(RazorpaySignatures.verifyPayment(orderId, paymentId, signature, KEY_SECRET));
    }

    @Test
    @DisplayName("payment signature: rejects a mismatched signature")
    void paymentSignatureRejected() {
        String orderId = "order_1";
        String paymentId = "pay_1";
        // Signature computed over a DIFFERENT order id — the HMAC should not match.
        String signature = hmacHex("order_2|" + paymentId, KEY_SECRET);

        assertFalse(RazorpaySignatures.verifyPayment(orderId, paymentId, signature, KEY_SECRET));
    }

    @Test
    @DisplayName("payment signature: rejects when the secret is blank")
    void paymentSignatureRequiresSecret() {
        // A blank secret would otherwise sign anything; the helper must refuse before HMAC.
        assertFalse(RazorpaySignatures.verifyPayment("order_1", "pay_1", "ignored", ""));
        assertFalse(RazorpaySignatures.verifyPayment("order_1", "pay_1", "ignored", null));
    }

    @Test
    @DisplayName("payment signature: rejects a malformed hex signature")
    void paymentSignatureRejectsMalformedHex() {
        String orderId = "order_1";
        String paymentId = "pay_1";
        // Not hex — would decode to no bytes and must not match any HMAC output.
        assertFalse(RazorpaySignatures.verifyPayment(orderId, paymentId, "zzzz", KEY_SECRET));
        // Odd-length hex is also not a valid signature.
        assertFalse(RazorpaySignatures.verifyPayment(orderId, paymentId, "abc", KEY_SECRET));
    }

    @Test
    @DisplayName("webhook signature: accepts the signature Razorpay would send over the raw body")
    void webhookSignatureAccepted() {
        byte[] rawBody = "{\"event\":\"payment.captured\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
        String signature = hmacHex(new String(rawBody, StandardCharsets.UTF_8), WEBHOOK_SECRET);

        assertTrue(RazorpaySignatures.verifyWebhook(rawBody, signature, WEBHOOK_SECRET));
    }

    @Test
    @DisplayName("webhook signature: a single whitespace change breaks the match")
    void webhookSignatureSensitiveToBody() {
        // Razorpay signs the exact bytes they sent; a reformatting on our side (even one that
        // preserves JSON semantics) would re-sign to a different value. Verify we fail closed.
        byte[] original = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
        byte[] reformatted = "{\"event\": \"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
        String signatureForOriginal = hmacHex(new String(original, StandardCharsets.UTF_8), WEBHOOK_SECRET);

        assertTrue(RazorpaySignatures.verifyWebhook(original, signatureForOriginal, WEBHOOK_SECRET));
        assertFalse(RazorpaySignatures.verifyWebhook(reformatted, signatureForOriginal, WEBHOOK_SECRET));
    }

    @Test
    @DisplayName("webhook signature: rejects when the secret is blank")
    void webhookSignatureRequiresSecret() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertFalse(RazorpaySignatures.verifyWebhook(body, "deadbeef", ""));
        assertFalse(RazorpaySignatures.verifyWebhook(body, "deadbeef", null));
    }

    @Test
    @DisplayName("webhook and payment signatures do not accept each other's keys")
    void keysNotInterchangeable() {
        // If one secret could stand in for the other, a leaked webhook secret could sign
        // payment confirmations — this verifies the compare is per-key.
        String orderId = "order_X";
        String paymentId = "pay_Y";
        String signatureFromWebhookSecret = hmacHex(orderId + "|" + paymentId, WEBHOOK_SECRET);

        assertFalse(RazorpaySignatures.verifyPayment(orderId, paymentId, signatureFromWebhookSecret, KEY_SECRET));
    }

    private static String hmacHex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}

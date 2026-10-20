package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * HMAC-SHA256 helpers for the two Razorpay signatures this service verifies.
 *
 * <p>Both are HMAC-SHA256 and both are hex-encoded, but they are not interchangeable: Razorpay
 * uses different secrets and different payloads for each, and treating one as the other opens the
 * door to a crafted request being accepted. The two helpers below exist so callers cannot forget
 * which key or which payload they meant.
 *
 * <ul>
 *   <li>{@link #verifyPayment}: the signature Checkout returns to the browser. Keyed by the API
 *       secret, payload is {@code orderId + "|" + paymentId}. Used by {@code
 *       /credits/razorpay/verify}.
 *   <li>{@link #verifyWebhook}: the signature on an inbound webhook. Keyed by the webhook secret,
 *       payload is the raw request body (byte-for-byte, before any JSON parse — reformatting the
 *       body breaks the match). Used by {@code /credits/razorpay/webhook}.
 * </ul>
 *
 * <p>Comparison is constant-time via {@link MessageDigest#isEqual}; a byte-by-byte compare would
 * leak signature content through timing. Signature strings are compared after decoding to bytes
 * so an attacker cannot pad the hex form to match length. Doing it ourselves rather than through
 * the SDK's {@code Utils.verifyWebhookSignature} keeps the crypto obvious and testable, and shields
 * us from a weakening of the library's compare in a future bump.
 */
public final class RazorpaySignatures {

    private static final String HMAC_ALGO = "HmacSHA256";

    private RazorpaySignatures() {}

    public static boolean verifyPayment(String orderId, String paymentId,
                                        String providedSignatureHex, String keySecret) {
        if (orderId == null || paymentId == null || providedSignatureHex == null
                || keySecret == null || keySecret.isEmpty()) {
            return false;
        }
        byte[] expected = hmacSha256((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8),
                keySecret.getBytes(StandardCharsets.UTF_8));
        return matches(expected, providedSignatureHex);
    }

    public static boolean verifyWebhook(byte[] rawBody, String providedSignatureHex,
                                        String webhookSecret) {
        if (rawBody == null || providedSignatureHex == null
                || webhookSecret == null || webhookSecret.isEmpty()) {
            return false;
        }
        byte[] expected = hmacSha256(rawBody, webhookSecret.getBytes(StandardCharsets.UTF_8));
        return matches(expected, providedSignatureHex);
    }

    private static byte[] hmacSha256(byte[] payload, byte[] key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(key, HMAC_ALGO));
            return mac.doFinal(payload);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            // Every JRE ships HmacSHA256 and the key is non-empty by the time we are here.
            throw new IllegalStateException("HMAC-SHA256 not available", impossible);
        }
    }

    /** Hex-decodes the provided signature and compares byte-for-byte in constant time. */
    private static boolean matches(byte[] expectedBytes, String providedHex) {
        byte[] providedBytes = decodeHex(providedHex);
        if (providedBytes == null) return false;
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    private static byte[] decodeHex(String hex) {
        int len = hex.length();
        if ((len & 1) != 0) return null;
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}

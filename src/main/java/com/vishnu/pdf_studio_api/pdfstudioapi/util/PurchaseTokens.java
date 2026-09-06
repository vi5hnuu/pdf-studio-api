package com.vishnu.pdf_studio_api.pdfstudioapi.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives the stable, indexable identity of a Google Play purchase token.
 *
 * <p>Play tokens run to several hundred characters, which MySQL cannot index without a prefix — and
 * a prefix shorter than the token means two different purchases can collide. Hashing gives a
 * fixed 64-character key that indexes exactly.
 */
public final class PurchaseTokens {

    private PurchaseTokens() {}

    /** @return lowercase hex SHA-256 of the token, or null when the token is null. */
    public static String hash(String purchaseToken) {
        if (purchaseToken == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(purchaseToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

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

    /**
     * Builds a {@code credit_ledger.idempotency_key} for a purchase-related balance change.
     *
     * <p>The key must hold the token's identity — two different purchases may never share one, or
     * the unique {@code (user_id, idempotency_key)} would silently swallow the second grant — but
     * the column is {@code VARCHAR(100)} and a Play token runs to several hundred characters.
     * Embedding the raw token therefore overflowed the column, and because the ledger insert is
     * issued inside the crediting transaction, the whole grant rolled back: the buyer was charged
     * and credited nothing. Hashing keeps the identity and fits, and is deterministic, so a retried
     * token still collides on the unique key exactly as before.
     *
     * @return {@code "<prefix>:<64 hex chars>"}, or null when the token is null.
     */
    public static String ledgerKey(String prefix, String purchaseToken) {
        String hashed = hash(purchaseToken);
        return hashed == null ? null : prefix + ":" + hashed;
    }

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

package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

/**
 * Which payment provider a credit movement came from. Stored as the lowercase name on
 * credit_ledger and purchase_audit_logs.
 *
 * <p>Only billing flows set this. Non-billing credit movements (daily allowance, rewarded ad,
 * guest transfer, admin grant with no money changing hands, debit) leave the column null —
 * their provenance is their {@code reason}.
 */
public enum BillingChannel {
    PLAY,       // Google Play Billing (Android)
    RAZORPAY,   // Razorpay (web, planned follow-up PR)
    ADMIN;      // A credit granted by an operator (support case, promo credit, etc.)

    /** Lowercase form stored on the row — see V5__single_issuer_and_channel.sql. */
    public String code() {
        return name().toLowerCase();
    }
}

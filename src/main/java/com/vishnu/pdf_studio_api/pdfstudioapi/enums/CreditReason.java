package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

/** Why a credit-ledger row exists (the cause of a balance change). */
public enum CreditReason {
    WELCOME,      // one-time grant when a user is first seen
    PURCHASE,     // Google Play INAPP credit pack
    DEBIT,        // consumed by a paid tool
    REWARDED_AD,  // granted for watching a rewarded ad
    DAILY,        // daily free allowance claim
    REVOKE        // clawback for a refunded/voided purchase
}

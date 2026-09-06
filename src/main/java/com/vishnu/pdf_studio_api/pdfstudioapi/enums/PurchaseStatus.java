package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

/** Terminal state of a Google Play purchase-verification attempt (audit log). */
public enum PurchaseStatus {
    GRANTED,              // verified with Play and credits applied
    VERIFICATION_FAILED,  // Play could not verify the token
    CREDITS_REVOKED       // clawed back after a refund/void RTDN
}

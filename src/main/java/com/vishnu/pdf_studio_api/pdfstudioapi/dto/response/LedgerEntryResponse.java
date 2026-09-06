package com.vishnu.pdf_studio_api.pdfstudioapi.dto.response;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CreditReason;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditLedger;

import java.time.Instant;

/**
 * One row of a user's credit history.
 *
 * <p>A deliberately narrow projection of {@link CreditLedger}: the entity also holds the
 * client IP and the idempotency key, neither of which the owning user needs and both of
 * which are better not echoed back to a client.
 *
 * @param delta        signed change — negative for a debit, positive for a grant
 * @param balanceAfter balance immediately after this row, so a client can render a running
 *                     total without recomputing it from a partial page
 */
public record LedgerEntryResponse(
        Long id,
        String toolId,
        int delta,
        CreditReason reason,
        int balanceAfter,
        Instant createdAt
) {
    public static LedgerEntryResponse from(CreditLedger row) {
        return new LedgerEntryResponse(
                row.getId(), row.getToolId(), row.getDelta(),
                row.getReason(), row.getBalanceAfter(), row.getCreatedAt());
    }
}

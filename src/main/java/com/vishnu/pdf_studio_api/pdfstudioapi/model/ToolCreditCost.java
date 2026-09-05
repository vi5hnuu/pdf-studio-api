package com.vishnu.pdf_studio_api.pdfstudioapi.model;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.SizeUnit;
import jakarta.persistence.*;
import lombok.*;

/**
 * The credit price of a tool, resolved server-side so a tampered client cannot underpay.
 *
 * <p>Cost = {@code baseCredits + (size / unitSize) * creditsPerUnit} when a size unit
 * applies (integer division — the base covers the first block), else just {@code baseCredits}.
 * A {@code baseCredits} of 0 means the tool is free. Editable at runtime (no redeploy).
 */
@Entity
@Table(name = "tool_credit_costs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolCreditCost {

    /** Stable tool identifier — the endpoint path suffix, e.g. "compress-pdf". */
    @Id
    @Column(name = "tool_id", length = 64, nullable = false, updatable = false)
    private String toolId;

    @Column(name = "base_credits", nullable = false)
    private int baseCredits;

    @Enumerated(EnumType.STRING)
    @Column(name = "size_unit", nullable = false, length = 16)
    @Builder.Default
    private SizeUnit sizeUnit = SizeUnit.NONE;

    /** Extra credits per {@link #unitSize} block of the size unit. */
    @Column(name = "credits_per_unit", nullable = false)
    @Builder.Default
    private int creditsPerUnit = 0;

    /** Block size for the size component, in bytes (e.g. 5_000_000 = one credit per 5 MB). */
    @Column(name = "unit_size", nullable = false)
    @Builder.Default
    private long unitSize = 1;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * Computes the total credit cost for a given input size in bytes (per {@link #sizeUnit}).
     * A non-positive/irrelevant size just yields the base.
     */
    public int computeCost(long sizeMetric) {
        if (!active) return 0;
        if (sizeUnit == SizeUnit.NONE || creditsPerUnit <= 0 || unitSize <= 0 || sizeMetric <= 0) {
            return baseCredits;
        }
        long extraBlocks = sizeMetric / unitSize;
        return (int) (baseCredits + extraBlocks * creditsPerUnit);
    }
}

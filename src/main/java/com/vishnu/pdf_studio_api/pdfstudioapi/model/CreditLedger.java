package com.vishnu.pdf_studio_api.pdfstudioapi.model;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CreditReason;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Append-only audit of every balance change (grant or debit). Provides a full history
 * and backs idempotency: a unique {@code (user_id, idempotency_key)} prevents a retried
 * request from charging/granting twice.
 */
@Entity
@Table(name = "credit_ledger",
        uniqueConstraints = @UniqueConstraint(name = "uq_ledger_user_idem",
                columnNames = {"user_id", "idempotency_key"}),
        indexes = @Index(name = "idx_ledger_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 40, nullable = false)
    private String userId;

    /** Tool that caused a DEBIT; null for grants. */
    @Column(name = "tool_id", length = 64)
    private String toolId;

    /** Signed change: negative for debits, positive for grants. */
    @Column(name = "delta", nullable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 16)
    private CreditReason reason;

    /** Client- or server-supplied key making the operation idempotent; null = not deduped. */
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}

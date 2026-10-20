package com.vishnu.pdf_studio_api.pdfstudioapi.model;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CreditReason;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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
        indexes = {
                @Index(name = "idx_ledger_user", columnList = "user_id"),
                @Index(name = "idx_ledger_user_reason_time", columnList = "user_id, reason, created_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    /** Tool that caused a DEBIT; null for grants. */
    @Column(name = "tool_id", length = 64)
    private String toolId;

    /** Signed change: negative for debits, positive for grants. */
    @Column(name = "delta", nullable = false)
    private int delta;

    // Hibernate 6 maps an enum to MySQL's native enum(...) type by default, which the
    // VARCHAR in the migration does not match — the service then refuses to start under
    // ddl-auto=validate. VARCHAR is also the better column: adding a value to the enum
    // does not require an ALTER TABLE.
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 16)
    private CreditReason reason;

    /**
     * Which payment provider funded this movement (play, razorpay, admin). Null for the
     * non-billing flows (daily, rewarded_ad, debit, transfer) — their reason is enough.
     * Stored as the lowercase name — see {@link
     * com.vishnu.pdf_studio_api.pdfstudioapi.enums.BillingChannel#code()}.
     */
    @Column(name = "channel", length = 16)
    private String channel;

    /** Client- or server-supplied key making the operation idempotent; null = not deduped. */
    /**
     * Widened to 512 by V3 to match CreditsService.MAX_PURCHASE_TOKEN_LENGTH, so the column can
     * hold anything the service accepts. The service itself stores a hash for purchase keys and
     * bounds caller-supplied ones, so in practice nothing written here exceeds about 80.
     */
    @Column(name = "idempotency_key", length = 512)
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

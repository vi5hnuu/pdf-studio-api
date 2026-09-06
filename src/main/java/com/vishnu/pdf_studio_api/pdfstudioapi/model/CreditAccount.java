package com.vishnu.pdf_studio_api.pdfstudioapi.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A user's credit balance, keyed by the auth service's {@code userId} (JWT subject).
 *
 * <p>This service stores no identity — only the balance and the bookkeeping needed for
 * the daily-allowance claim. Mutated atomically under a pessimistic lock by
 * {@code CreditsService} to prevent double-spend/double-grant races.
 */
@Entity
@Table(name = "credit_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditAccount {

    /**
     * Namespaced auth id: {@code "<issuer alias>:<sub>"}.
     *
     * <p>Not the bare JWT subject — the app's and the web's auth deployments have separate
     * databases and generate ids independently, so two unrelated people can hold the same subject.
     * See {@link com.vishnu.pdf_studio_api.pdfstudioapi.security.PrincipalKey}.
     */
    @Id
    @Column(name = "user_id", length = 64, nullable = false, updatable = false)
    private String userId;

    @Column(name = "balance", nullable = false)
    private int balance;

    /** Calendar date (UTC) of the last daily-allowance claim; null if never claimed. */
    @Column(name = "last_daily_claim_date")
    private LocalDate lastDailyClaimDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}

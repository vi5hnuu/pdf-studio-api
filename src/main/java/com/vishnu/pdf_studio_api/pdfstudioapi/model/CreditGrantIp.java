package com.vishnu.pdf_studio_api.pdfstudioapi.model;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.GrantKind;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * How many free grants of a kind an IP has taken on a given day.
 *
 * <p>Free credits are otherwise rationed per account, which is only meaningful when accounts are
 * expensive to obtain. The web tier hands out guest accounts on demand, so clearing site data and
 * reloading would mint an unlimited series of welcome balances. Counting per IP as well closes that
 * without punishing genuine users: the caps are per <em>day</em>, and set high enough that a shared
 * office or campus address still works.
 *
 * <p>The IP is stored only as a salted hash — enough to count against, never enough to identify
 * someone from the table.
 */
@Entity
@Table(name = "credit_ip_grants",
        uniqueConstraints = @UniqueConstraint(name = "uq_grant_ip_kind_day",
                columnNames = {"ip_hash", "grant_kind", "grant_date"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditGrantIp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 of the client IP plus a server-side salt. */
    @Column(name = "ip_hash", length = 64, nullable = false, updatable = false)
    private String ipHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "grant_kind", length = 16, nullable = false, updatable = false)
    private GrantKind grantKind;

    /** UTC calendar date the counter applies to. */
    @Column(name = "grant_date", nullable = false, updatable = false)
    private LocalDate grantDate;

    @Column(name = "grant_count", nullable = false)
    private int grantCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}

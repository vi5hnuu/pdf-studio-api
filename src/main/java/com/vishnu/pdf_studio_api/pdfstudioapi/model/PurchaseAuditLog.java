package com.vishnu.pdf_studio_api.pdfstudioapi.model;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PurchaseStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Records every Google Play credit-purchase verification outcome.
 *
 * <p>The unique {@code (purchase_token, status)} key is the backstop against double-grant
 * (a token can be GRANTED at most once, across all users) and lets a refund/void RTDN
 * claw back exactly the amount that was granted.
 */
@Entity
@Table(name = "purchase_audit_log",
        uniqueConstraints = @UniqueConstraint(name = "uq_pal_token_status",
                columnNames = {"purchase_token", "status"}),
        indexes = @Index(name = "idx_pal_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 40, nullable = false)
    private String userId;

    @Column(name = "purchase_token", columnDefinition = "TEXT", nullable = false)
    private String purchaseToken;

    @Column(name = "order_id", length = 128)
    private String orderId;

    @Column(name = "product_id", length = 100, nullable = false)
    private String productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PurchaseStatus status;

    /** Credits granted on a GRANTED row; null otherwise. Used for precise refund clawback. */
    @Column(name = "credits_granted")
    private Integer creditsGranted;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}

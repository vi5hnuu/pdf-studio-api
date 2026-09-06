package com.vishnu.pdf_studio_api.pdfstudioapi.model;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PurchaseStatus;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.PurchaseTokens;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;

import java.time.Instant;

/**
 * Records every Google Play credit-purchase verification outcome.
 *
 * <p>The unique {@code (token_hash, status)} key is the backstop against double-grant (a token can
 * be GRANTED at most once, across all users) and lets a refund/void RTDN claw back exactly the
 * amount that was granted.
 *
 * <p>The key is on a SHA-256 of the token rather than the token itself. MySQL cannot index a
 * {@code TEXT} column without a prefix length, and the previous {@code purchase_token(255)} prefix
 * key was shorter than a real Play token (~300+ characters) — so two distinct purchases sharing a
 * 255-character prefix would have collided, and the second, legitimate one would have been rejected
 * as "already redeemed". A fixed-width hash indexes exactly and cannot collide in practice.
 */
@Entity
@Table(name = "purchase_audit_logs",
        uniqueConstraints = @UniqueConstraint(name = "uq_pal_token_hash_status",
                columnNames = {"token_hash", "status"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "purchase_token", length = 512, nullable = false)
    private String purchaseToken;

    /** SHA-256 (hex) of {@link #purchaseToken}; the indexed, collision-free identity of a purchase. */
    @Column(name = "token_hash", length = 64, nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "order_id", length = 128)
    private String orderId;

    @Column(name = "product_id", length = 100, nullable = false)
    private String productId;

    // Hibernate 6 maps an enum to MySQL's native enum(...) type by default, which the
    // VARCHAR in the migration does not match — the service then refuses to start under
    // ddl-auto=validate. VARCHAR is also the better column: adding a value to the enum
    // does not require an ALTER TABLE.
    @JdbcTypeCode(SqlTypes.VARCHAR)
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
        if (tokenHash == null) tokenHash = PurchaseTokens.hash(purchaseToken);
    }
}

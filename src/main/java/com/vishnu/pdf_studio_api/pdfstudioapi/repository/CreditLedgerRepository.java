package com.vishnu.pdf_studio_api.pdfstudioapi.repository;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CreditReason;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface CreditLedgerRepository extends JpaRepository<CreditLedger, Long> {

    boolean existsByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    Optional<CreditLedger> findFirstByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    /** Count of grant events of a reason for a user since an instant (rewarded-ad daily cap). */
    long countByUserIdAndReasonAndCreatedAtAfter(String userId, CreditReason reason, Instant since);
}

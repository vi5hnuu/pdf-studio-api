package com.vishnu.pdf_studio_api.pdfstudioapi.repository;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.GrantKind;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditGrantIp;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface CreditGrantIpRepository extends JpaRepository<CreditGrantIp, Long> {

    /**
     * Locks the counter row so two simultaneous first-requests from one IP cannot both read the
     * same count and both be allowed through.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT g FROM CreditGrantIp g
           WHERE g.ipHash = :ipHash AND g.grantKind = :kind AND g.grantDate = :date
           """)
    Optional<CreditGrantIp> findForUpdate(@Param("ipHash") String ipHash,
                                          @Param("kind") GrantKind kind,
                                          @Param("date") LocalDate date);

    /** Housekeeping: the counters only matter for the current day. */
    void deleteByGrantDateBefore(LocalDate date);
}

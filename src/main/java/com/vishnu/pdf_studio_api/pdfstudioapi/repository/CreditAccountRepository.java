package com.vishnu.pdf_studio_api.pdfstudioapi.repository;

import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CreditAccountRepository extends JpaRepository<CreditAccount, String> {

    /** Pessimistic write-lock the row so balance mutations are serialized per user. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM CreditAccount a WHERE a.userId = :userId")
    Optional<CreditAccount> findForUpdate(@Param("userId") String userId);
}

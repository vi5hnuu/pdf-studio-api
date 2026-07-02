package com.vishnu.pdf_studio_api.pdfstudioapi.repository;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PurchaseStatus;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.PurchaseAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PurchaseAuditLogRepository extends JpaRepository<PurchaseAuditLog, Long> {

    boolean existsByPurchaseTokenAndStatus(String purchaseToken, PurchaseStatus status);

    Optional<PurchaseAuditLog> findFirstByPurchaseTokenAndStatus(String purchaseToken, PurchaseStatus status);
}

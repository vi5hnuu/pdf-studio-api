package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CreditReason;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PurchaseStatus;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditLedger;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.PurchaseAuditLog;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditAccountRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditLedgerRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.PurchaseAuditLogRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.PurchaseTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The RazorpayService webhook dispatch — not the HMAC, which is covered unit-side. These tests
 * stand up Spring Boot with the pack catalogue configured but Razorpay credentials still blank,
 * so order creation is disabled (no SDK client) and the webhook path uses the real DB writer
 * end-to-end. Each test asserts the business effect of one event type.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // Catalogue is needed for payment.captured validation; credentials stay blank so the
        // Razorpay SDK is never contacted.
        "app.razorpay.packs.pdfcraft_credits_10.amount-paise=5000",
        "app.razorpay.packs.pdfcraft_credits_10.credits=10",
        "app.razorpay.packs.pdfcraft_credits_30.amount-paise=13000",
        "app.razorpay.packs.pdfcraft_credits_30.credits=30",
})
class RazorpayWebhookDispatchTest {

    @Autowired RazorpayService service;
    @Autowired CreditAccountRepository accountRepository;
    @Autowired CreditLedgerRepository ledgerRepository;
    @Autowired PurchaseAuditLogRepository auditRepository;
    @Autowired ObjectMapper mapper;

    @Test
    @DisplayName("payment.captured grants the pack's credits with channel=razorpay")
    void paymentCapturedGrantsCredits() throws Exception {
        String user = ulid();
        String paymentId = "pay_" + randomSuffix();
        String orderId = "order_" + randomSuffix();

        service.handleWebhook(event(paymentCapturedBody(user, paymentId, orderId, "pdfcraft_credits_10", 10)));

        int balance = accountRepository.findById(user).orElseThrow().getBalance();
        // 10 purchased + whatever the account started with (welcome grant).
        assertTrue(balance >= 10);

        List<CreditLedger> rows = ledgerRepository.findAll().stream()
                .filter(l -> user.equals(l.getUserId())
                        && CreditReason.PURCHASE.equals(l.getReason()))
                .toList();
        assertEquals(1, rows.size(), "exactly one PURCHASE row per payment");
        assertEquals(10, rows.get(0).getDelta());
        assertEquals("razorpay", rows.get(0).getChannel(),
                "channel on the ledger row is what distinguishes Razorpay from Play in reporting");

        PurchaseAuditLog audit = auditRepository
                .findFirstByTokenHashAndStatus(PurchaseTokens.hash(paymentId), PurchaseStatus.GRANTED)
                .orElseThrow();
        assertEquals(user, audit.getUserId());
        assertEquals(orderId, audit.getOrderId());
        assertEquals("razorpay", audit.getChannel());
    }

    @Test
    @DisplayName("payment.captured is idempotent on repeated delivery")
    void paymentCapturedIdempotent() throws Exception {
        String user = ulid();
        String paymentId = "pay_" + randomSuffix();
        String orderId = "order_" + randomSuffix();

        service.handleWebhook(event(paymentCapturedBody(user, paymentId, orderId, "pdfcraft_credits_30", 30)));
        int afterFirst = accountRepository.findById(user).orElseThrow().getBalance();

        // Razorpay delivers at-least-once; the second delivery must not double-credit.
        service.handleWebhook(event(paymentCapturedBody(user, paymentId, orderId, "pdfcraft_credits_30", 30)));
        int afterSecond = accountRepository.findById(user).orElseThrow().getBalance();

        assertEquals(afterFirst, afterSecond, "a replay must not grant twice");
        long purchaseRows = ledgerRepository.findAll().stream()
                .filter(l -> user.equals(l.getUserId())
                        && CreditReason.PURCHASE.equals(l.getReason()))
                .count();
        assertEquals(1L, purchaseRows);
    }

    @Test
    @DisplayName("payment.captured with a credit count that disagrees with the server catalogue is refused")
    void paymentCapturedRejectsTamperedCreditCount() throws Exception {
        String user = ulid();
        String paymentId = "pay_" + randomSuffix();
        String orderId = "order_" + randomSuffix();

        // Notes claim 1 000 credits for a 10-credit pack — a client-tampered order. The webhook
        // is signed by Razorpay but the notes travel through client code before order creation,
        // so a product/credit mismatch is grounds to refuse the grant.
        service.handleWebhook(event(paymentCapturedBody(user, paymentId, orderId, "pdfcraft_credits_10", 1_000)));

        assertTrue(accountRepository.findById(user).isEmpty()
                        || accountRepository.findById(user).orElseThrow().getBalance() < 1_000,
                "no account should be funded by a mismatched-credit webhook");
    }

    @Test
    @DisplayName("payment.failed records a VERIFICATION_FAILED audit row and grants nothing")
    void paymentFailedRecordsAudit() throws Exception {
        String user = ulid();
        String paymentId = "pay_" + randomSuffix();

        service.handleWebhook(event(paymentFailedBody(user, paymentId, "pdfcraft_credits_10")));

        PurchaseAuditLog audit = auditRepository
                .findFirstByTokenHashAndStatus(PurchaseTokens.hash(paymentId),
                        PurchaseStatus.VERIFICATION_FAILED)
                .orElseThrow();
        assertEquals(user, audit.getUserId());
        assertEquals("razorpay", audit.getChannel());
        assertTrue(ledgerRepository.findAll().stream()
                        .filter(l -> user.equals(l.getUserId()))
                        .noneMatch(l -> CreditReason.PURCHASE.equals(l.getReason())),
                "payment.failed must leave the balance untouched");
    }

    @Test
    @DisplayName("refund.processed revokes exactly the credits the prior grant added")
    void refundRevokesCredits() throws Exception {
        String user = ulid();
        String paymentId = "pay_" + randomSuffix();
        String orderId = "order_" + randomSuffix();

        service.handleWebhook(event(paymentCapturedBody(user, paymentId, orderId, "pdfcraft_credits_30", 30)));
        int afterGrant = accountRepository.findById(user).orElseThrow().getBalance();

        service.handleWebhook(event(refundProcessedBody(paymentId)));
        int afterRefund = accountRepository.findById(user).orElseThrow().getBalance();

        assertEquals(afterGrant - 30, afterRefund);
        auditRepository
                .findFirstByTokenHashAndStatus(PurchaseTokens.hash(paymentId), PurchaseStatus.CREDITS_REVOKED)
                .orElseThrow();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private JsonNode event(String body) throws Exception {
        return mapper.readTree(body);
    }

    private static String paymentCapturedBody(String user, String paymentId, String orderId,
                                              String productId, int credits) {
        // The server validates status + amount against the catalogue. Hard-code the matching
        // amount per pack so the body passes the strict check.
        int amount = switch (productId) {
            case "pdfcraft_credits_10" -> 5000;
            case "pdfcraft_credits_30" -> 13000;
            case "pdfcraft_credits_60" -> 23000;
            default -> -1; // mismatch — exercises the catalogue-check rejection path
        };
        return """
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "order_id": "%s",
                        "status": "captured",
                        "amount": %d,
                        "notes": { "user_id": "%s", "product_id": "%s", "credits": %d }
                      }
                    }
                  }
                }
                """.formatted(paymentId, orderId, amount, user, productId, credits);
    }

    private static String paymentFailedBody(String user, String paymentId, String productId) {
        return """
                {
                  "event": "payment.failed",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "notes": { "user_id": "%s", "product_id": "%s" }
                      }
                    }
                  }
                }
                """.formatted(paymentId, user, productId);
    }

    private static String refundProcessedBody(String paymentId) {
        return """
                {
                  "event": "refund.processed",
                  "payload": {
                    "refund": {
                      "entity": { "payment_id": "%s" }
                    }
                  }
                }
                """.formatted(paymentId);
    }

    private static String ulid() {
        // The validator accepts [A-Za-z0-9._-]{1,64}; a UUID with hyphens is fine.
        return UUID.randomUUID().toString();
    }

    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

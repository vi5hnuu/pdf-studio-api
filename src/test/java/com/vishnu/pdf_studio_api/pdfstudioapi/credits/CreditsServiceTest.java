package com.vishnu.pdf_studio_api.pdfstudioapi.credits;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PurchaseStatus;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.PurchaseAuditLog;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditLedgerRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.PurchaseAuditLogRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.CreditsService;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.PurchaseTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the money-handling paths: debits, idempotency, and purchase-token identity. */
@SpringBootTest
class CreditsServiceTest {

    @Autowired CreditsService creditsService;
    @Autowired CreditLedgerRepository ledgerRepository;
    @Autowired PurchaseAuditLogRepository purchaseAuditLogRepository;

    private String newUser() {
        return "u-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("a new user gets the welcome grant and a debit reduces it")
    void debitsFromWelcomeBalance() {
        String user = newUser();
        int opening = creditsService.getBalance(user, null);
        assertTrue(opening > 0, "welcome grant should seed a balance");

        // grayscale-pdf is seeded at 1 credit.
        var result = creditsService.charge(user, "grayscale-pdf", 0L, null, null);
        assertEquals(1, result.charged());
        assertEquals(opening - 1, result.balanceRemaining());
        assertEquals(opening - 1, creditsService.getBalance(user, null));
    }

    @Test
    @DisplayName("the same Idempotency-Key charges exactly once, however many times it is sent")
    void idempotentRetryIsNotDoubleCharged() {
        String user = newUser();
        int opening = creditsService.getBalance(user, null);
        String key = "idem-" + UUID.randomUUID();

        var first = creditsService.charge(user, "grayscale-pdf", 0L, key, null);
        assertFalse(first.alreadyCharged());
        assertEquals(opening - 1, first.balanceRemaining());

        // A client that never saw the first response resends it.
        var retry = creditsService.charge(user, "grayscale-pdf", 0L, key, null);
        assertTrue(retry.alreadyCharged(), "retry should be recognised, not charged again");
        assertEquals(opening - 1, creditsService.getBalance(user, null),
                "balance must not move on a retry");
        assertTrue(creditsService.alreadyCharged(user, key));
    }

    @Test
    @DisplayName("refuses the debit with 402 once the balance cannot cover it")
    void refusesWhenShort() {
        String user = newUser();
        int opening = creditsService.getBalance(user, null);
        // Drain the account one credit at a time.
        for (int i = 0; i < opening; i++) {
            creditsService.charge(user, "grayscale-pdf", 0L, "drain-" + i + "-" + user, null);
        }
        assertEquals(0, creditsService.getBalance(user, null));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> creditsService.charge(user, "grayscale-pdf", 0L, null, null));
        assertEquals(HttpStatus.PAYMENT_REQUIRED, ex.getStatusCode());
    }

    @Test
    @DisplayName("size-priced tools add the per-block surcharge to the base")
    void appliesSizeSurcharge() {
        // compress-pdf is seeded at base 2, +1 per 5 MB.
        assertEquals(2, creditsService.resolveCost("compress-pdf", 0L));
        assertEquals(2, creditsService.resolveCost("compress-pdf", 4_000_000L));
        assertEquals(3, creditsService.resolveCost("compress-pdf", 5_000_000L));
        assertEquals(4, creditsService.resolveCost("compress-pdf", 12_000_000L));
    }

    @Test
    @DisplayName("an unpriced tool resolves to free rather than failing the request")
    void unknownToolIsFree() {
        assertEquals(0, creditsService.resolveCost("no-such-tool-" + UUID.randomUUID(), 0L));
    }

    @Test
    @DisplayName("tokens sharing a 255-character prefix are treated as distinct purchases")
    void longTokensDoNotCollide() {
        // The old UNIQUE(purchase_token(255), status) key would have rejected the second of these.
        String shared = "x".repeat(300);
        String tokenA = shared + "AAA";
        String tokenB = shared + "BBB";
        assertNotEquals(PurchaseTokens.hash(tokenA), PurchaseTokens.hash(tokenB));

        purchaseAuditLogRepository.save(PurchaseAuditLog.builder()
                .userId(newUser()).purchaseToken(tokenA).productId("pdfcraft_credits_10")
                .status(PurchaseStatus.GRANTED).creditsGranted(10).build());

        assertDoesNotThrow(() -> purchaseAuditLogRepository.save(PurchaseAuditLog.builder()
                        .userId(newUser()).purchaseToken(tokenB).productId("pdfcraft_credits_10")
                        .status(PurchaseStatus.GRANTED).creditsGranted(10).build()),
                "a different token sharing a 255-char prefix must not collide");

        assertTrue(purchaseAuditLogRepository.existsByTokenHashAndStatus(
                PurchaseTokens.hash(tokenA), PurchaseStatus.GRANTED));
        assertTrue(purchaseAuditLogRepository.existsByTokenHashAndStatus(
                PurchaseTokens.hash(tokenB), PurchaseStatus.GRANTED));
    }

    @Test
    @DisplayName("every debit is recorded in the ledger with the resulting balance")
    void writesLedgerRows() {
        String user = newUser();
        String key = "ledger-" + UUID.randomUUID();
        var result = creditsService.charge(user, "grayscale-pdf", 0L, key, null);

        var row = ledgerRepository.findFirstByUserIdAndIdempotencyKey(user, key);
        assertTrue(row.isPresent(), "a debit must leave an audit trail");
        assertEquals(-1, row.get().getDelta());
        assertEquals(result.balanceRemaining(), row.get().getBalanceAfter());
    }

    @Test
    @DisplayName("a guest's credits move to the account they sign in to")
    void transfersGuestBalance() {
        String guest = "web:" + UUID.randomUUID();
        String account = "app:" + UUID.randomUUID();

        int guestOpening = creditsService.getBalance(guest, null);
        int accountOpening = creditsService.getBalance(account, null);
        assertTrue(guestOpening > 0, "the guest should have earned its welcome credits");

        int moved = creditsService.transferGuestBalance(guest, account, null);

        assertEquals(guestOpening, moved);
        assertEquals(0, creditsService.getBalance(guest, null), "the guest is drained");
        assertEquals(accountOpening + guestOpening, creditsService.getBalance(account, null));
    }

    @Test
    @DisplayName("signing in twice does not move the credits twice")
    void transferIsIdempotent() {
        String guest = "web:" + UUID.randomUUID();
        String account = "app:" + UUID.randomUUID();
        creditsService.getBalance(guest, null);
        int accountOpening = creditsService.getBalance(account, null);

        int first = creditsService.transferGuestBalance(guest, account, null);
        int second = creditsService.transferGuestBalance(guest, account, null);

        assertTrue(first > 0);
        assertEquals(0, second, "a repeated transfer moves nothing");
        assertEquals(accountOpening + first, creditsService.getBalance(account, null));
    }

    @Test
    @DisplayName("transferring to the same account, or from an empty guest, is a no-op")
    void transferNoOps() {
        String account = "app:" + UUID.randomUUID();
        creditsService.getBalance(account, null);

        assertEquals(0, creditsService.transferGuestBalance(account, account, null));
        assertEquals(0, creditsService.transferGuestBalance("web:never-seen-" + UUID.randomUUID(), account, null));
    }
}

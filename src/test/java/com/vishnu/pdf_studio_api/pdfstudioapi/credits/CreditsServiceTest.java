package com.vishnu.pdf_studio_api.pdfstudioapi.credits;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PurchaseStatus;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditLedger;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.PurchaseAuditLog;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditLedgerRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.PurchaseAuditLogRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.AuthInternalClient;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.CreditsService;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.PurchaseTokens;
import jakarta.persistence.Column;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Covers the money-handling paths: debits, idempotency, and purchase-token identity. */
@SpringBootTest
class CreditsServiceTest {

    @Autowired CreditsService creditsService;
    @Autowired CreditLedgerRepository ledgerRepository;
    @Autowired PurchaseAuditLogRepository purchaseAuditLogRepository;

    @MockBean AuthInternalClient authInternal;

    @BeforeEach
    void callerIsNotAGuest() {
        // purchaseCredits gates on isGuest; a signed-in caller must appear as not-a-guest for
        // the token-length guard under test to be reached at all.
        when(authInternal.isGuest(anyString())).thenReturn(Optional.of(false));
    }

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
        var result = creditsService.charge(user, "grayscale-pdf", 0L, "k-" + user, null);
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
                () -> creditsService.charge(user, "grayscale-pdf", 0L, "short-" + user, null));
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
    @DisplayName("a purchase ledger key fits idempotency_key, however long the Play token is")
    void purchaseLedgerKeyFitsItsColumn() throws Exception {
        // The crediting transaction writes this key, so a key longer than the column rolls the
        // whole grant back: the buyer is charged and credited nothing. The limit is read off the
        // entity rather than hardcoded, so shrinking the column fails here instead of in produc-
        // tion. A real Play token is 300+ characters.
        int columnLength = CreditLedger.class.getDeclaredField("idempotencyKey")
                .getAnnotation(Column.class).length();

        for (String token : new String[]{"x".repeat(300), "y".repeat(1024), "z"}) {
            for (String prefix : new String[]{"purchase", "revoke"}) {
                String key = PurchaseTokens.ledgerKey(prefix, token);
                assertTrue(key.length() <= columnLength,
                        () -> prefix + " key is " + key.length() + " chars, column holds " + columnLength);
            }
        }

        // Identity has to survive the hashing, or two purchases would share one key and the
        // unique (user_id, idempotency_key) would silently drop the second grant.
        assertNotEquals(PurchaseTokens.ledgerKey("purchase", "token-a"),
                PurchaseTokens.ledgerKey("purchase", "token-b"));
        // ...and the same token must still collide, which is what makes a retry idempotent.
        assertEquals(PurchaseTokens.ledgerKey("purchase", "token-a"),
                PurchaseTokens.ledgerKey("purchase", "token-a"));
        // A revoke must not cancel out its own grant's key.
        assertNotEquals(PurchaseTokens.ledgerKey("purchase", "token-a"),
                PurchaseTokens.ledgerKey("revoke", "token-a"));
    }

    @Test
    @DisplayName("a purchase token longer than its column is rejected as a 400")
    void overlongPurchaseTokenIsRejected() {
        var ex = assertThrows(ResponseStatusException.class, () -> creditsService.purchaseCredits(
                newUser(), "x".repeat(513), "pdfcraft_credits_10", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
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
    @DisplayName("an over-long Idempotency-Key header is bounded, and a normal one is left alone")
    void callerSuppliedKeysAreBounded() throws Exception {
        // Idempotency-Key is whatever the client sends. Our app sends about 44 characters, but
        // nothing enforced that, so a longer header reached the insert unchanged and MySQL
        // rejected the row — a 500 on a charged request.
        int columnLength = CreditLedger.class.getDeclaredField("idempotencyKey")
                .getAnnotation(Column.class).length();

        String ours = "19a3f2b1c4d-0123456789abcdef0123456789abcdef";
        assertEquals(ours, PurchaseTokens.boundedKey(ours),
                "a key that already fits must pass through, or stored rows stop matching on retry");

        String huge = "k".repeat(4096);
        assertTrue(PurchaseTokens.boundedKey(huge).length() <= columnLength);
        // Still deterministic, so a retry of the same request still dedupes.
        assertEquals(PurchaseTokens.boundedKey(huge), PurchaseTokens.boundedKey(huge));
        assertNotEquals(PurchaseTokens.boundedKey(huge), PurchaseTokens.boundedKey("j".repeat(4096)));

        assertNull(PurchaseTokens.boundedKey(null));
    }
}

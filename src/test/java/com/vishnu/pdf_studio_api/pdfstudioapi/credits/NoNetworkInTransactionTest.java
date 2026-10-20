package com.vishnu.pdf_studio_api.pdfstudioapi.credits;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PurchaseStatus;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.PurchaseAuditLogRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.PurchaseTokens;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.AuthInternalClient;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.CreditsService;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.PlayStoreVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Holds the line on the rule that network calls do not happen inside a database transaction.
 *
 * <p>They used to. Verifying a purchase with Google Play, and telling the auth service to retain
 * the account, both ran inside the transaction that wrote the grant — the second of them after
 * the account row had been locked. A pooled connection, and a {@code SELECT ... FOR UPDATE} row
 * lock, were therefore held for the length of an HTTP round trip. The pool holds ten connections,
 * so a slow dependency could stall every request in the application, and the RTDN webhook that
 * drives reconciliation is delivered in bursts.
 *
 * <p>Asserted on the real property rather than on the annotations, because that is what actually
 * matters and because a future refactor could restore the annotation without anyone noticing.
 */
@SpringBootTest
class NoNetworkInTransactionTest {

    private static final String PRODUCT = "pdfcraft_credits_10";

    @Autowired CreditsService creditsService;
    @Autowired PurchaseAuditLogRepository auditRepository;

    @MockBean PlayStoreVerifier playStoreVerifier;
    @MockBean AuthInternalClient authInternal;

    /** True if a transaction was open when Play was called. */
    private AtomicBoolean recordVerifyContext(String accountId) {
        final AtomicBoolean inTransaction = new AtomicBoolean(true);
        when(playStoreVerifier.verifyInAppPurchase(anyString(), anyString())).thenAnswer(call -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new PlayStoreVerifier.InAppPurchaseResult(true, "order-1", accountId);
        });
        return inTransaction;
    }

    @Test
    @DisplayName("redeeming a purchase calls Play with no transaction open")
    void purchaseVerifiesOutsideATransaction() {
        final String user = "u-" + UUID.randomUUID();
        var inTransaction = recordVerifyContext(user);

        creditsService.purchaseCredits(user, "tok-" + UUID.randomUUID(), PRODUCT, null);

        assertFalse(inTransaction.get(),
                "Play was called inside a transaction, holding a pooled connection across the network");
    }

    @Test
    @DisplayName("RTDN reconciliation calls Play with no transaction open")
    void reconcileVerifiesOutsideATransaction() {
        final String user = "u-" + UUID.randomUUID();
        var inTransaction = recordVerifyContext(user);

        creditsService.reconcilePurchase("tok-" + UUID.randomUUID(), PRODUCT);

        assertFalse(inTransaction.get(),
                "the webhook path held a connection across the network, and it arrives in bursts");
    }

    @Test
    @DisplayName("claiming a purchase asks auth about the owner with no transaction open")
    void claimAsksAuthOutsideATransaction() {
        final String guest = "g-" + UUID.randomUUID();
        recordVerifyContext(guest);
        final String token = "tok-" + UUID.randomUUID();
        creditsService.purchaseCredits(guest, token, PRODUCT, null);

        final AtomicBoolean inTransaction = new AtomicBoolean(true);
        when(authInternal.isGuest(anyString())).thenAnswer(call -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(true);
        });

        creditsService.claimPurchase("new-" + UUID.randomUUID(), token, PRODUCT, null);

        assertFalse(inTransaction.get(),
                "auth was called inside a transaction that also takes two account row locks");
    }

    @Test
    @DisplayName("the grant itself is still transactional, so a failed audit rolls the credit back")
    void theWriteIsStillAtomic() {
        // Moving the network out must not have moved the atomicity out with it: the balance and
        // its ledger row and its audit row still have to land together or not at all.
        final String user = "u-" + UUID.randomUUID();
        recordVerifyContext(user);
        final String token = "tok-" + UUID.randomUUID();

        int before = creditsService.getBalance(user, null);
        creditsService.purchaseCredits(user, token, PRODUCT, null);
        int after = creditsService.getBalance(user, null);
        assertTrue(after > before);

        // A replay is reported, not granted again, which is only true if the first audit row
        // committed with the credit.
        var replay = creditsService.purchaseCredits(user, token, PRODUCT, null);
        assertTrue(replay.alreadyGranted());
        assertEquals(0, replay.creditsGranted());
        assertEquals(after, creditsService.getBalance(user, null));
    }

    @Test
    @DisplayName("a rejected purchase is still recorded, even though the call then fails")
    void verificationFailureSurvivesTheRejection() {
        // This used to be lost. The audit row was written and the method then threw
        // ResponseStatusException — a RuntimeException — from inside the same transaction, so
        // Spring rolled the row back on the way out. Every rejected purchase was silently
        // unrecorded, which is exactly the trail you want when someone is probing with forged
        // tokens. It survives now because the write owns a transaction that commits first.
        when(playStoreVerifier.verifyInAppPurchase(anyString(), anyString()))
                .thenReturn(new PlayStoreVerifier.InAppPurchaseResult(false, null, null));

        final String user = "u-" + UUID.randomUUID();
        final String token = "tok-" + UUID.randomUUID();

        assertThrows(ResponseStatusException.class,
                () -> creditsService.purchaseCredits(user, token, PRODUCT, null));

        assertTrue(
                auditRepository.existsByTokenHashAndStatus(
                        PurchaseTokens.hash(token), PurchaseStatus.VERIFICATION_FAILED),
                "the rejection left no audit trail");
    }
}

package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CreditReason;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PurchaseStatus;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditAccount;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditLedger;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.PurchaseAuditLog;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditAccountRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditLedgerRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.PurchaseAuditLogRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.PurchaseTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Every write to a credit balance, and the transaction each one runs in.
 *
 * <p>A separate bean so the flows that need to call Google Play or the auth service can do that
 * work <em>outside</em> a database transaction. They used to be transactional end to end, which
 * meant a pooled connection — and, after the account was locked, a {@code SELECT ... FOR UPDATE}
 * row lock — was held for the length of an HTTP round trip. With a connection pool of ten, a slow
 * dependency could stall every other request in the application. Network first, then one short
 * transaction, is the shape that cannot do that.
 *
 * <p>A second bean rather than private methods because Spring's transactions are proxy-based: a
 * call from one method of a class to another bypasses the proxy and silently runs with no
 * transaction at all. {@link IpGrantGuard} and {@link CreditGrantIpCounter} are split for the same
 * reason.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditLedgerWriter {

    private final CreditAccountRepository accountRepository;
    private final CreditLedgerRepository ledgerRepository;
    private final PurchaseAuditLogRepository purchaseAuditLogRepository;
    private final CreditAccountService accountService;

    // ── Primitives, joining whatever transaction the caller already has ──────────────

    /** Locks the account row so concurrent balance changes serialise rather than interleave. */
    public CreditAccount lockAccount(String userId) {
        return accountRepository.findForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit account not found."));
    }

    /** Applies a signed delta to the locked account and writes the matching ledger row. */
    public void applyDelta(CreditAccount account, int delta, CreditReason reason,
                           String toolId, String idempotencyKey, String ip) {
        account.setBalance(account.getBalance() + delta);
        accountRepository.save(account);
        ledgerRepository.save(CreditLedger.builder()
                .userId(account.getUserId())
                .toolId(toolId)
                .delta(delta)
                .reason(reason)
                .idempotencyKey(idempotencyKey)
                .balanceAfter(account.getBalance())
                .ip(ip)
                .build());
    }

    public void saveAudit(String userId, String token, String orderId, String productId,
                          PurchaseStatus status, Integer creditsGranted, String ip) {
        purchaseAuditLogRepository.save(PurchaseAuditLog.builder()
                .userId(userId).purchaseToken(token).orderId(orderId).productId(productId)
                .status(status).creditsGranted(creditsGranted).ip(ip).build());
    }

    // ── Operations that own their transaction ───────────────────────────────────────

    /**
     * Credits a verified purchase to its owner, exactly once.
     *
     * <p>Called once the purchase has already been verified with Play, so this does no I/O beyond
     * the database and the transaction stays short.
     *
     * @return the owner's balance afterwards
     * @throws ResponseStatusException CONFLICT when a concurrent call granted the same token first
     */
    @Transactional
    public int grantPurchase(String owner, String purchaseToken, String productId,
                             String orderId, int credits, String ip) {
        accountService.ensure(owner, ip);
        CreditAccount account = lockAccount(owner);
        applyDelta(account, credits, CreditReason.PURCHASE, null,
                PurchaseTokens.ledgerKey("purchase", purchaseToken), ip);
        try {
            saveAudit(owner, purchaseToken, orderId, productId,
                    PurchaseStatus.GRANTED, credits, ip);
        } catch (DataIntegrityViolationException dup) {
            // Lost the race on uq(token_hash, GRANTED). The credits exist, on whichever account
            // won; rolling this one back is what keeps the grant single.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This purchase is being redeemed.");
        }
        return account.getBalance();
    }

    /** Records that Play refused a token, once, so repeated attempts leave one row. */
    @Transactional
    public void recordVerificationFailure(String userId, String purchaseToken, String productId, String ip) {
        if (purchaseAuditLogRepository.existsByTokenHashAndStatus(
                PurchaseTokens.hash(purchaseToken), PurchaseStatus.VERIFICATION_FAILED)) {
            return;
        }
        saveAudit(userId, purchaseToken, null, productId, PurchaseStatus.VERIFICATION_FAILED, null, ip);
    }

    /**
     * Moves a guest's whole balance to another account, once.
     *
     * <p>Idempotent twice over: the ledger's unique {@code (user_id, idempotency_key)} records a
     * repeated transfer once, and the guest is drained to zero so a replay has nothing to move.
     *
     * @return credits moved
     */
    @Transactional
    public int transferGuestBalance(String guestUserId, String targetUserId, String ip) {
        if (guestUserId == null || guestUserId.equals(targetUserId)) return 0;

        var guestAccount = accountRepository.findForUpdate(guestUserId).orElse(null);
        if (guestAccount == null || guestAccount.getBalance() <= 0) return 0;

        final int amount = guestAccount.getBalance();
        final String key = "guest-transfer:" + guestUserId;

        // Already moved (a retried sign-in) — leave both balances alone.
        if (ledgerRepository.existsByUserIdAndIdempotencyKey(targetUserId, key)) return 0;

        accountService.ensure(targetUserId, ip);
        CreditAccount target = lockAccount(targetUserId);

        applyDelta(guestAccount, -amount, CreditReason.TRANSFER, null, key, ip);
        applyDelta(target, amount, CreditReason.TRANSFER, null, key, ip);

        log.info("Transferred {} credit(s) from guest {} to {}", amount, guestUserId, targetUserId);
        return amount;
    }
}

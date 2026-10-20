package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.BillingChannel;
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

    /**
     * Applies a signed delta to the locked account and writes the matching ledger row.
     *
     * <p>{@code channel} says which payment provider funded a billing movement (play, razorpay,
     * admin); pass null for non-billing flows (daily, rewarded_ad, debit) — the {@code reason} is
     * sufficient on its own.
     *
     * <p>{@code idempotencyKey} is required: the ledger's uq(user_id, idempotency_key) does not
     * dedupe NULLs under MySQL semantics, so a null key quietly becomes a fresh row on every
     * retry. The precondition fails fast at every call site rather than wait for a double-write
     * to show up in a reporting query.
     */
    public void applyDelta(CreditAccount account, int delta, CreditReason reason,
                           String toolId, String idempotencyKey, String ip, BillingChannel channel) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "idempotencyKey is required for every ledger row — null disables deduplication.");
        }
        account.setBalance(account.getBalance() + delta);
        accountRepository.save(account);
        ledgerRepository.save(CreditLedger.builder()
                .userId(account.getUserId())
                .toolId(toolId)
                .delta(delta)
                .reason(reason)
                .channel(channel == null ? null : channel.code())
                .idempotencyKey(idempotencyKey)
                .balanceAfter(account.getBalance())
                .ip(ip)
                .build());
    }

    public void saveAudit(String userId, String token, String orderId, String productId,
                          PurchaseStatus status, Integer creditsGranted, String ip,
                          BillingChannel channel) {
        purchaseAuditLogRepository.save(PurchaseAuditLog.builder()
                .userId(userId).purchaseToken(token).orderId(orderId).productId(productId)
                .channel(channel == null ? null : channel.code())
                .status(status).creditsGranted(creditsGranted).ip(ip).build());
    }

    // ── Operations that own their transaction ───────────────────────────────────────

    /**
     * Credits a verified purchase to its owner, exactly once.
     *
     * <p>Called once the purchase has already been verified with Play, so this does no I/O beyond
     * the database and the transaction stays short.
     *
     * <p>Two callers race on the same purchase: the client's {@code /claim-purchase} and the Play
     * RTDN webhook's {@code reconcilePurchase}. Both compute the same idempotency key
     * {@code purchase:<token>}. The DB's {@code uq(user_id, idempotency_key)} on
     * {@code credit_ledger} keeps the grant single — one write wins, the loser's insert throws
     * {@link DataIntegrityViolationException} and this transaction rolls back.
     *
     * <p>The loser's error is <em>not</em> caught here on purpose. Hibernate marks the transaction
     * rollback-only the moment the constraint fires during flush, so swallowing the exception
     * inside this method would make Spring throw {@code UnexpectedRollbackException} at commit
     * time — surfacing to the client as a 500 even though the credits are safely on the account.
     * {@code CreditsService.purchaseCredits} catches the exception in a non-transactional context,
     * reads the current balance, and returns a success outcome.
     *
     * @return the owner's balance afterwards
     */
    @Transactional
    public int grantPurchase(String owner, String purchaseToken, String productId,
                             String orderId, int credits, String ip, BillingChannel channel) {
        accountService.ensure(owner, ip);
        CreditAccount account = lockAccount(owner);
        applyDelta(account, credits, CreditReason.PURCHASE, null,
                PurchaseTokens.ledgerKey("purchase", purchaseToken), ip, channel);
        try {
            saveAudit(owner, purchaseToken, orderId, productId,
                    PurchaseStatus.GRANTED, credits, ip, channel);
        } catch (DataIntegrityViolationException dup) {
            // Lost the race on uq(token_hash, GRANTED). The credits exist, on whichever account
            // won; rolling this one back is what keeps the grant single. The exception propagates
            // out, Spring rolls back cleanly.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This purchase is being redeemed.");
        }
        return account.getBalance();
    }

    /**
     * Records that a billing provider refused a token, at most once, so repeated attempts leave
     * one row. Takes the channel so the audit trail says whether Play or Razorpay rejected the
     * payment — otherwise the two provider's failures become indistinguishable in reporting.
     */
    @Transactional
    public void recordVerificationFailureIfFirst(String userId, String purchaseToken,
                                                 String productId, String ip,
                                                 BillingChannel channel) {
        if (purchaseAuditLogRepository.existsByTokenHashAndStatus(
                PurchaseTokens.hash(purchaseToken), PurchaseStatus.VERIFICATION_FAILED)) {
            return;
        }
        saveAudit(userId, purchaseToken, null, productId, PurchaseStatus.VERIFICATION_FAILED, null, ip,
                channel);
    }

}

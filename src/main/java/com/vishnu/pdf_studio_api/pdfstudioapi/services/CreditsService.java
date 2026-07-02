package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.CreditProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CreditReason;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PurchaseStatus;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Manages the per-user credit balance: server-authoritative debits per paid tool, free
 * grants (welcome/daily/rewarded-ad) and Google Play INAPP purchases with refund clawback.
 *
 * <p>All mutations take a pessimistic write lock on the account row so concurrent requests
 * for the same user can't double-spend or double-grant. Costs are resolved from the DB tool
 * table — the client never supplies an amount.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditsService {

    /** Play INAPP product id → credits granted. Mirror in Play Console. */
    private static final Map<String, Integer> PRODUCT_CREDITS = Map.of(
            "pdfcraft_credits_10", 10,
            "pdfcraft_credits_30", 30,
            "pdfcraft_credits_60", 60
    );

    private final CreditAccountService accountService;
    private final CreditAccountRepository accountRepository;
    private final CreditLedgerRepository ledgerRepository;
    private final ToolCreditCostRepository costRepository;
    private final PurchaseAuditLogRepository purchaseAuditLogRepository;
    private final PlayStoreVerifier playStoreVerifier;
    private final CreditProperties creditProperties;

    // ── Balance & pricing ───────────────────────────────────────────────────────

    public int getBalance(String userId, String ip) {
        return accountService.ensure(userId, ip).getBalance();
    }

    public List<ToolCreditCost> listCosts() {
        return costRepository.findByActiveTrueOrderByToolIdAsc();
    }

    /** Resolves a tool's cost for the given size metric; unknown tool ⇒ 0 (free, fail-open). */
    public int resolveCost(String toolId, long sizeMetric) {
        return costRepository.findById(toolId).map(c -> c.computeCost(sizeMetric)).orElse(0);
    }

    /** True if a debit with this idempotency key already happened (a retry). */
    public boolean alreadyCharged(String userId, String idempotencyKey) {
        return idempotencyKey != null && !idempotencyKey.isBlank()
                && ledgerRepository.existsByUserIdAndIdempotencyKey(userId, idempotencyKey);
    }

    // ── Debit (paid tools) ──────────────────────────────────────────────────────

    /**
     * Charges the resolved cost for a tool and records the debit, atomically.
     * Idempotent when {@code idempotencyKey} is supplied — a retried request returns the
     * original result without charging again.
     *
     * @throws ResponseStatusException 402 when the balance is insufficient.
     */
    @Transactional
    public ChargeResult charge(String userId, String toolId, long sizeMetric,
                               String idempotencyKey, String ip) {
        accountService.ensure(userId, ip);
        CreditAccount account = lockAccount(userId);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = ledgerRepository.findFirstByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                return new ChargeResult(account.getBalance(), -existing.get().getDelta(), true);
            }
        }

        final int cost = resolveCost(toolId, sizeMetric);
        if (cost <= 0) {
            return new ChargeResult(account.getBalance(), 0, false); // free tool — no debit
        }
        if (account.getBalance() < cost) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Not enough credits. This tool costs " + cost + " credit(s); you have "
                            + account.getBalance() + ".");
        }

        applyDelta(account, -cost, CreditReason.DEBIT, toolId, idempotencyKey, ip);
        log.info("Charged {} credit(s) for tool={} userId={} remaining={}", cost, toolId, userId, account.getBalance());
        return new ChargeResult(account.getBalance(), cost, false);
    }

    // ── Free grants ─────────────────────────────────────────────────────────────

    /** Grants rewarded-ad credits, enforcing a per-day cap. Idempotent per {@code idempotencyKey}. */
    @Transactional
    public GrantResult grantRewardedAd(String userId, String idempotencyKey, String ip) {
        accountService.ensure(userId, ip);
        CreditAccount account = lockAccount(userId);

        if (idempotencyKey != null && !idempotencyKey.isBlank()
                && ledgerRepository.existsByUserIdAndIdempotencyKey(userId, idempotencyKey)) {
            return new GrantResult(account.getBalance(), 0);
        }

        final int grant = creditProperties.getRewardedAdGrant();
        final long grantedToday = ledgerRepository.countByUserIdAndReasonAndCreatedAtAfter(
                userId, CreditReason.REWARDED_AD, startOfTodayUtc());
        if ((grantedToday + 1) * grant > creditProperties.getRewardedAdDailyCap()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You've reached today's rewarded-credit limit. Come back tomorrow.");
        }

        applyDelta(account, grant, CreditReason.REWARDED_AD, null, idempotencyKey, ip);
        return new GrantResult(account.getBalance(), grant);
    }

    /** Grants the daily free allowance; idempotent per calendar day (UTC). */
    @Transactional
    public GrantResult claimDailyAllowance(String userId, String ip) {
        accountService.ensure(userId, ip);
        CreditAccount account = lockAccount(userId);

        final LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (today.equals(account.getLastDailyClaimDate())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Daily credits already claimed today. Come back tomorrow.");
        }
        final int grant = creditProperties.getDailyAllowance();
        account.setLastDailyClaimDate(today);
        applyDelta(account, grant, CreditReason.DAILY, null, "daily:" + userId + ":" + today, ip);
        return new GrantResult(account.getBalance(), grant);
    }

    // ── Purchases ─────────────────────────────────────────────────────────────────

    /**
     * Verifies a Google Play INAPP token and credits the account exactly once.
     * @throws ResponseStatusException on unknown product, duplicate token, or verify failure.
     */
    @Transactional
    public int purchaseCredits(String userId, String purchaseToken, String productId, String ip) {
        if (purchaseToken == null || purchaseToken.length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid purchase token.");
        }
        final Integer creditsToAdd = PRODUCT_CREDITS.get(productId);
        if (creditsToAdd == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown product: " + productId);
        }
        if (purchaseAuditLogRepository.existsByPurchaseTokenAndStatus(purchaseToken, PurchaseStatus.GRANTED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This purchase was already redeemed.");
        }

        var result = playStoreVerifier.verifyInAppPurchase(productId, purchaseToken);
        if (!result.valid()) {
            if (!purchaseAuditLogRepository.existsByPurchaseTokenAndStatus(
                    purchaseToken, PurchaseStatus.VERIFICATION_FAILED)) {
                saveAudit(userId, purchaseToken, null, productId, PurchaseStatus.VERIFICATION_FAILED, null, ip);
            }
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Google Play could not verify this purchase.");
        }

        accountService.ensure(userId, ip);
        CreditAccount account = lockAccount(userId);
        applyDelta(account, creditsToAdd, CreditReason.PURCHASE, null,
                "purchase:" + purchaseToken, ip);
        try {
            saveAudit(userId, purchaseToken, result.orderId(), productId,
                    PurchaseStatus.GRANTED, creditsToAdd, ip);
        } catch (DataIntegrityViolationException dup) {
            // Concurrent grant lost the race on uq(purchase_token, GRANTED) — roll back this one.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This purchase was already redeemed.");
        }
        log.info("Credits purchased: userId={} product={} +{} balance={}",
                userId, productId, creditsToAdd, account.getBalance());
        return account.getBalance();
    }

    /** Claws back a refunded/voided purchase's credits (RTDN). Idempotent; balance may go negative. */
    @Transactional
    public void revokeCreditsForToken(String purchaseToken) {
        if (purchaseToken == null || purchaseToken.isBlank()) return;
        if (purchaseAuditLogRepository.existsByPurchaseTokenAndStatus(purchaseToken, PurchaseStatus.CREDITS_REVOKED)) {
            return; // already handled (RTDN is at-least-once)
        }
        var grantedOpt = purchaseAuditLogRepository.findFirstByPurchaseTokenAndStatus(
                purchaseToken, PurchaseStatus.GRANTED);
        if (grantedOpt.isEmpty()) return;

        var granted = grantedOpt.get();
        Integer amount = granted.getCreditsGranted();
        if (amount == null) amount = PRODUCT_CREDITS.get(granted.getProductId());
        if (amount == null) return;

        CreditAccount account = accountRepository.findForUpdate(granted.getUserId()).orElse(null);
        if (account == null) return;
        applyDelta(account, -amount, CreditReason.REVOKE, null, "revoke:" + purchaseToken, null);
        saveAudit(granted.getUserId(), purchaseToken, granted.getOrderId(), granted.getProductId(),
                PurchaseStatus.CREDITS_REVOKED, null, null);
        log.info("Credits revoked (refund/void): userId={} product={} -{} balance={}",
                granted.getUserId(), granted.getProductId(), amount, account.getBalance());
    }

    // ── internal helpers ──────────────────────────────────────────────────────────

    private CreditAccount lockAccount(String userId) {
        return accountRepository.findForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit account not found."));
    }

    /** Applies a signed delta to the locked account and writes the matching ledger row. */
    private void applyDelta(CreditAccount account, int delta, CreditReason reason,
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

    private void saveAudit(String userId, String token, String orderId, String productId,
                           PurchaseStatus status, Integer creditsGranted, String ip) {
        purchaseAuditLogRepository.save(PurchaseAuditLog.builder()
                .userId(userId).purchaseToken(token).orderId(orderId).productId(productId)
                .status(status).creditsGranted(creditsGranted).ip(ip).build());
    }

    private java.time.Instant startOfTodayUtc() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public record ChargeResult(int balanceRemaining, int charged, boolean alreadyCharged) {}
    public record GrantResult(int balance, int granted) {}
}

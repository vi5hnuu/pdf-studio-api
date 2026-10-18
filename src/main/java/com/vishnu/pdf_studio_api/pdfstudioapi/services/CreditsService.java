package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.CreditProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CreditReason;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.GrantKind;
import com.vishnu.pdf_studio_api.pdfstudioapi.dto.response.LedgerEntryResponse;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PurchaseStatus;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.security.PrincipalKey;
import org.springframework.beans.factory.annotation.Value;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.PurchaseTokens;
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

    /**
     * Longest purchase token accepted, matching {@code purchase_audit_logs.purchase_token}
     * (VARCHAR(512)). The guard used to allow 1024, so a token between the two lengths passed
     * validation and then failed the audit insert as a 500 instead of a clear 400.
     */
    private static final int MAX_PURCHASE_TOKEN_LENGTH = 512;

    private final CreditAccountService accountService;
    private final CreditAccountRepository accountRepository;
    private final CreditLedgerRepository ledgerRepository;
    private final ToolCreditCostRepository costRepository;
    private final PurchaseAuditLogRepository purchaseAuditLogRepository;
    private final PlayStoreVerifier playStoreVerifier;
    private final AuthInternalClient authInternal;
    private final CreditLedgerWriter ledgerWriter;
    private final CreditProperties creditProperties;
    private final IpGrantGuard ipGrantGuard;

    /**
     * Issuer alias for accounts that can make Play purchases. Play's obfuscated account id
     * carries the bare auth subject, which has to be namespaced the same way tokens are.
     */
    @Value("${app.play.account-id-alias:app}")
    private String playAccountAlias;

    // ── Balance & pricing ───────────────────────────────────────────────────────

    public int getBalance(String userId, String ip) {
        return accountService.ensure(userId, ip).getBalance();
    }

    /** Server-owned ad-free entitlement. The client caches this but never sets it. */
    public boolean isAdFree(String userId, String ip) {
        return accountService.ensure(userId, ip).isAdFree();
    }

    public List<ToolCreditCost> listCosts() {
        return costRepository.findByActiveTrueOrderByToolIdAsc();
    }

    /**
     * Resolves a tool's cost for the given input size in bytes.
     *
     * <p>An unknown tool id still yields 0 — refusing service because of a pricing gap would be
     * worse for the user than serving it free — but it is logged loudly, because it means a
     * premium tool is silently free. {@code ToolCostConsistencyChecker} turns the same condition
     * into a startup failure so it is caught before deployment rather than in the billing data.
     */
    public int resolveCost(String toolId, long sizeBytes) {
        var cost = costRepository.findById(toolId);
        if (cost.isEmpty()) {
            log.warn("No tool_credit_costs row for tool '{}' — serving it FREE. Add a row to price it.",
                    toolId);
            return 0;
        }
        return cost.get().computeCost(sizeBytes);
    }

    /**
     * Current balance without creating an account.
     *
     * <p>Used by the charging aspect's fast pre-check: {@link #getBalance} creates the account (in
     * its own transaction) as a side effect, which is wasted work on the pre-check path since
     * {@link #charge} ensures it anyway.
     *
     * @return empty when the user has no account yet
     */
    public java.util.OptionalInt peekBalance(String userId) {
        return accountRepository.findById(userId)
                .map(a -> java.util.OptionalInt.of(a.getBalance()))
                .orElseGet(java.util.OptionalInt::empty);
    }

    /** True if a debit with this idempotency key already happened (a retry). */
    public boolean alreadyCharged(String userId, String idempotencyKey) {
        final String key = PurchaseTokens.boundedKey(idempotencyKey);
        return key != null && !key.isBlank()
                && ledgerRepository.existsByUserIdAndIdempotencyKey(userId, key);
    }

    /**
     * A page of the caller's credit history, newest first.
     *
     * <p>"Where did my credits go?" is the first question a credit economy generates, and
     * until now the ledger existed but nothing exposed it — leaving support to answer from
     * the database by hand.
     *
     * @param page zero-based; {@code size} is clamped so a client cannot request the lot
     */
    public LedgerPage listLedger(String userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        var result = ledgerRepository.findByUserIdOrderByIdDesc(
                userId, org.springframework.data.domain.PageRequest.of(safePage, safeSize));
        return new LedgerPage(
                result.getContent().stream().map(LedgerEntryResponse::from).toList(),
                safePage,
                safeSize,
                result.getTotalElements(),
                result.hasNext());
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
    public ChargeResult charge(String userId, String toolId, long sizeBytes,
                               String idempotencyKey, String ip) {
        return charge(userId, toolId, resolveCost(toolId, sizeBytes), idempotencyKey, ip);
    }

    /**
     * As {@link #charge(String, String, long, String, String)} but with the cost already resolved,
     * so the caller's pre-check and this debit agree on one price and hit the cost table once.
     */
    @Transactional
    public ChargeResult charge(String userId, String toolId, int cost,
                               String idempotencyKey, String ip) {
        accountService.ensure(userId, ip);
        CreditAccount account = ledgerWriter.lockAccount(userId);

        // Bounded here rather than at the insert, so the lookup above and the row below use the
        // same key — normalising only one of them would stop a retry deduping.
        idempotencyKey = PurchaseTokens.boundedKey(idempotencyKey);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = ledgerRepository.findFirstByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                return new ChargeResult(account.getBalance(), -existing.get().getDelta(), true);
            }
        }

        if (cost <= 0) {
            return new ChargeResult(account.getBalance(), 0, false); // free tool — no debit
        }
        if (account.getBalance() < cost) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Not enough credits. This tool costs " + cost + " credit(s); you have "
                            + account.getBalance() + ".");
        }

        ledgerWriter.applyDelta(account, -cost, CreditReason.DEBIT, toolId, idempotencyKey, ip);
        log.info("Charged {} credit(s) for tool={} userId={} remaining={}", cost, toolId, userId, account.getBalance());
        return new ChargeResult(account.getBalance(), cost, false);
    }

    // ── Free grants ─────────────────────────────────────────────────────────────

    /** Grants rewarded-ad credits, enforcing a per-day cap. Idempotent per {@code idempotencyKey}. */
    @Transactional
    public GrantResult grantRewardedAd(String userId, String idempotencyKey, String ip) {
        idempotencyKey = PurchaseTokens.boundedKey(idempotencyKey);
        accountService.ensure(userId, ip);
        CreditAccount account = ledgerWriter.lockAccount(userId);

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

        ledgerWriter.applyDelta(account, grant, CreditReason.REWARDED_AD, null, idempotencyKey, ip);
        return new GrantResult(account.getBalance(), grant);
    }

    /** Grants the daily free allowance; idempotent per calendar day (UTC). */
    @Transactional
    public GrantResult claimDailyAllowance(String userId, String ip) {
        accountService.ensure(userId, ip);
        CreditAccount account = ledgerWriter.lockAccount(userId);

        final LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (today.equals(account.getLastDailyClaimDate())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Daily credits already claimed today. Come back tomorrow.");
        }
        // Also rationed per IP: a per-account daily limit means little when new accounts are free.
        if (!ipGrantGuard.tryConsume(ip, GrantKind.DAILY)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many free claims from your network today. Please try again tomorrow.");
        }
        final int grant = creditProperties.getDailyAllowance();
        account.setLastDailyClaimDate(today);
        ledgerWriter.applyDelta(account, grant, CreditReason.DAILY, null, "daily:" + userId + ":" + today, ip);
        return new GrantResult(account.getBalance(), grant);
    }

    /**
     * Moves a guest's remaining balance onto the account they just signed in to.
     *
     * <p>The web hands out a guest session so tools work before anyone signs in, and that
     * guest earns the welcome and daily credits. Signing in switches the caller to a
     * different auth instance and therefore a different account key, so without this their
     * credits would simply vanish at the moment they created an account — the worst possible
     * time to lose something.
     *
     * <p>Idempotent: the ledger's unique {@code (user_id, idempotency_key)} means a repeated
     * transfer of the same guest is recorded once, and the guest is drained to zero so a
     * replay has nothing left to move.
     *
     * @return credits moved
     */
    public int transferGuestBalance(String guestUserId, String targetUserId, String ip) {
        return ledgerWriter.transferGuestBalance(guestUserId, targetUserId, ip);
    }

    // ── Purchases ─────────────────────────────────────────────────────────────────

    /**
     * Verifies a Google Play INAPP token and credits the purchase's owner exactly once.
     *
     * <p>The owner is the account Play recorded at purchase time, not whoever happens to be
     * calling. The app attaches its user id as the obfuscated external account id, so Play is the
     * durable record of who bought what — which matters because a session is not durable. A guest
     * whose refresh token is rejected, or whose account the auth service purges after its expiry,
     * silently becomes a different principal; binding the grant to the caller meant the credits
     * landed on whichever identity happened to be live at redemption.
     *
     * <p>Never throws CONFLICT for an already-granted token. It used to, and the client read that
     * as "the credits exist" and consumed the purchase — so a redelivered purchase belonging to a
     * previous identity was spent and could never be redelivered again. The response now says who
     * was credited and lets the caller decide.
     */
    // Deliberately NOT @Transactional. Verifying with Play is a network round trip, and holding
    // a pooled connection across it — then a row lock, once the account is locked — is how a slow
    // dependency stalls the whole application. The write below owns its own short transaction.
    public PurchaseOutcome purchaseCredits(String callerId, String purchaseToken, String productId, String ip) {
        if (purchaseToken == null || purchaseToken.length() > MAX_PURCHASE_TOKEN_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid purchase token.");
        }
        final Integer creditsToAdd = PRODUCT_CREDITS.get(productId);
        if (creditsToAdd == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown product: " + productId);
        }

        // Cheap path first: an already-granted token needs no call to Play, because the audit row
        // already records who the grant went to.
        var alreadyGranted = purchaseAuditLogRepository.findFirstByTokenHashAndStatus(
                PurchaseTokens.hash(purchaseToken), PurchaseStatus.GRANTED);
        if (alreadyGranted.isPresent()) {
            String owner = alreadyGranted.get().getUserId();
            return new PurchaseOutcome(getBalance(callerId, ip), 0, true, owner.equals(callerId));
        }

        var result = playStoreVerifier.verifyInAppPurchase(productId, purchaseToken);
        if (!result.valid()) {
            ledgerWriter.recordVerificationFailure(callerId, purchaseToken, productId, ip);
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Google Play could not verify this purchase.");
        }

        // Falls back to the caller when Play has no account id — an older client that did not
        // attach one. reconcilePurchase makes the same allowance for the same reason.
        final String owner = (result.accountId() == null || result.accountId().isBlank())
                ? callerId
                : PrincipalKey.of(playAccountAlias, result.accountId());

        final int ownerBalance = ledgerWriter.grantPurchase(
                owner, purchaseToken, productId, result.orderId(), creditsToAdd, ip);

        // A guest that has paid must outlive the auth service's guest expiry, or its credits are
        // orphaned when the account is purged. Non-blocking; see AuthInternalClient.retain.
        authInternal.retain(owner);

        log.info("Credits purchased: owner={} caller={} product={} +{} balance={}",
                owner, callerId, productId, creditsToAdd, ownerBalance);
        final boolean toCaller = owner.equals(callerId);
        return new PurchaseOutcome(toCaller ? ownerBalance : getBalance(callerId, ip),
                creditsToAdd, false, toCaller);
    }

    /**
     * Moves a purchase's credits to the caller when the account that owns them is an abandoned
     * guest.
     *
     * <p>For the case the rest of this class cannot fix on its own: a guest's session is gone —
     * its refresh token rejected, or the account purged by the auth service's guest expiry — so
     * the app is now a different principal, and the credits it paid for sit on an identity it can
     * no longer authenticate as. Play still reports the purchase, which is the one durable link
     * back to the original account.
     *
     * <p>Only an anonymous owner may be claimed. Holding a Play purchase token proves you are the
     * Google account that bought it, which is fair grounds to reclaim an <em>anonymous</em>
     * entitlement and no grounds at all to take over a real one: two people share a device, the
     * second signs in, and nothing they can present should move the first person's credits. A
     * full-account owner is therefore refused, and so is an owner whose type cannot be
     * established — unknown is not permission.
     *
     * @return credits moved; 0 when there was nothing to move
     */
    // Not @Transactional: verifying with Play and asking auth what kind of account owns the
    // purchase are both network calls, and the transfer below owns its own transaction.
    public int claimPurchase(String callerId, String purchaseToken, String productId, String ip) {
        if (purchaseToken == null || purchaseToken.length() > MAX_PURCHASE_TOKEN_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid purchase token.");
        }
        var granted = purchaseAuditLogRepository.findFirstByTokenHashAndStatus(
                PurchaseTokens.hash(purchaseToken), PurchaseStatus.GRANTED);
        if (granted.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No redeemed purchase matches this token.");
        }
        final String owner = granted.get().getUserId();
        if (owner.equals(callerId)) return 0; // already yours

        // The token has to be real and still valid, or anyone could claim against a made-up one.
        var result = playStoreVerifier.verifyInAppPurchase(productId, purchaseToken);
        if (!result.valid()) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Google Play could not verify this purchase.");
        }

        if (!authInternal.isGuest(owner).orElse(false)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This purchase belongs to a registered account. Sign in to that account to use its credits.");
        }

        // Same mechanics as signing in: drain the guest, credit the target, once.
        int moved = ledgerWriter.transferGuestBalance(owner, callerId, ip);
        if (moved > 0) {
            authInternal.retain(callerId);
            log.info("Purchase claimed: {} credit(s) moved from abandoned guest {} to {}",
                    moved, owner, callerId);
        }
        return moved;
    }

    /**
     * Credits a purchase the client never redeemed, driven by a Play RTDN.
     *
     * <p>Without this, a purchase that completes while the app is killed (or that fails to reach
     * {@code /credits/purchase} before the process dies) leaves the user charged by Google and
     * uncredited by us, with no path to recovery but a support ticket.
     *
     * <p>RTDN carries only the token, so the owning user comes from the obfuscated external account
     * id the client attaches when starting the purchase. If it is absent — an older client build —
     * this logs and does nothing rather than guessing an account to credit; the client's own
     * redemption still works.
     */
    // Not @Transactional, for the same reason as purchaseCredits: this runs from the RTDN
    // webhook, which Pub/Sub can deliver in bursts, and a Play call per delivery inside a
    // transaction would hold one of ten pooled connections for each.
    public void reconcilePurchase(String purchaseToken, String productId) {
        if (purchaseToken == null || purchaseToken.isBlank()) return;
        final Integer credits = PRODUCT_CREDITS.get(productId);
        if (credits == null) {
            log.warn("RTDN for unknown product '{}' — ignoring.", productId);
            return;
        }
        if (purchaseAuditLogRepository.existsByTokenHashAndStatus(
                PurchaseTokens.hash(purchaseToken), PurchaseStatus.GRANTED)) {
            return; // already credited, by the client or a previous delivery
        }

        var result = playStoreVerifier.verifyInAppPurchase(productId, purchaseToken);
        if (!result.valid()) {
            log.warn("RTDN purchase did not verify with Play — not crediting.");
            return;
        }
        if (result.accountId() == null || result.accountId().isBlank()) {
            log.warn("RTDN purchase has no obfuscated account id; leaving it for the client to redeem.");
            return;
        }

        // Play purchases only ever originate in the mobile app, so the raw subject the client
        // attached is namespaced with that issuer's alias to match how the account is keyed.
        final String userId = PrincipalKey.of(playAccountAlias, result.accountId());
        ledgerWriter.grantPurchase(userId, purchaseToken, productId, result.orderId(), credits, null);
        authInternal.retain(userId);

        log.info("Reconciled unredeemed purchase from RTDN: userId={} product={} +{}",
                userId, productId, credits);
    }

    /**
     * Claws back a refunded/voided purchase's credits. Idempotent; balance may go negative.
     *
     * <p>Driven by an RTDN in real time, and by the nightly sweep for anything that never arrived.
     *
     * @return true when this call actually revoked something, so a caller can report what it did
     */
    @Transactional
    public boolean revokeCreditsForToken(String purchaseToken) {
        if (purchaseToken == null || purchaseToken.isBlank()) return false;
        if (purchaseAuditLogRepository.existsByTokenHashAndStatus(PurchaseTokens.hash(purchaseToken), PurchaseStatus.CREDITS_REVOKED)) {
            return false; // already handled (RTDN is at-least-once)
        }
        var grantedOpt = purchaseAuditLogRepository.findFirstByTokenHashAndStatus(
                PurchaseTokens.hash(purchaseToken), PurchaseStatus.GRANTED);
        if (grantedOpt.isEmpty()) return false;

        var granted = grantedOpt.get();
        Integer amount = granted.getCreditsGranted();
        if (amount == null) amount = PRODUCT_CREDITS.get(granted.getProductId());
        if (amount == null) return false;

        CreditAccount account = accountRepository.findForUpdate(granted.getUserId()).orElse(null);
        if (account == null) return false;
        ledgerWriter.applyDelta(account, -amount, CreditReason.REVOKE, null,
                PurchaseTokens.ledgerKey("revoke", purchaseToken), null);
        ledgerWriter.saveAudit(granted.getUserId(), purchaseToken, granted.getOrderId(), granted.getProductId(),
                PurchaseStatus.CREDITS_REVOKED, null, null);
        log.info("Credits revoked (refund/void): userId={} product={} -{} balance={}",
                granted.getUserId(), granted.getProductId(), amount, account.getBalance());
        return true;
    }

    // ── internal helpers ──────────────────────────────────────────────────────────


    private java.time.Instant startOfTodayUtc() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * What a redemption did.
     *
     * @param callerBalance   the *caller's* balance after the call, which is what the client shows
     * @param creditsGranted  credits added by this call; 0 when the token was already redeemed
     * @param alreadyGranted  the token had been redeemed before, so this call changed nothing
     * @param creditedToCaller whether the grant belongs to the caller. False means the purchase is
     *        owned by another account and the caller's balance did not rise — the client must not
     *        infer otherwise, which is the assumption that used to destroy purchases.
     */
    public record PurchaseOutcome(int callerBalance, int creditsGranted,
                                  boolean alreadyGranted, boolean creditedToCaller) {}

    public record ChargeResult(int balanceRemaining, int charged, boolean alreadyCharged) {}

    /** One page of credit history plus what a client needs to fetch the next. */
    public record LedgerPage(List<LedgerEntryResponse> entries, int page, int size,
                             long totalEntries, boolean hasMore) {}
    public record GrantResult(int balance, int granted) {}
}

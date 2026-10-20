package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.CreditProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.BillingChannel;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CreditReason;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.GrantKind;
import com.vishnu.pdf_studio_api.pdfstudioapi.dto.response.LedgerEntryResponse;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PurchaseStatus;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.security.Subjects;
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

        ledgerWriter.applyDelta(account, -cost, CreditReason.DEBIT, toolId, idempotencyKey, ip, null);
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

        ledgerWriter.applyDelta(account, grant, CreditReason.REWARDED_AD, null, idempotencyKey, ip, null);
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
        ledgerWriter.applyDelta(account, grant, CreditReason.DAILY, null, "daily:" + userId + ":" + today, ip, null);
        return new GrantResult(account.getBalance(), grant);
    }

    // ── Guest guard ───────────────────────────────────────────────────────────────

    /**
     * Refuses the call unless the caller is a real signed-in account.
     *
     * <p>A guest earns welcome and daily credits, which is how new users try the paid tools
     * before signing up; they must not be able to <em>buy</em> credits or move balances between
     * accounts. Allowing either lets a signed-out session cycle its ULID (logout → auto-mint
     * fresh guest → +10 welcome → sign-in → transfer) and farm the opening balance; it also
     * leaves the credits a guest paid for stranded on an id the auth service will expire. Fixing
     * the economy at this one gate is simpler and tighter than any mitigation of transfer and
     * purchase paths that would still allow guests to touch money.
     *
     * <p>Fail-closed: if the auth service cannot be reached to tell us the account type, this
     * refuses the call. "Unknown" is not permission to spend real money.
     */
    public void requireSignedIn(String callerId) {
        Boolean isGuest = authInternal.isGuest(callerId).orElse(null);
        if (isGuest == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not confirm your account right now. Please try again.");
        }
        if (isGuest) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Guest accounts cannot buy credits. Sign in to continue.");
        }
    }

    // ── Purchases ─────────────────────────────────────────────────────────────────

    /**
     * Verifies a Google Play INAPP token and credits the caller exactly once.
     *
     * <p>Only signed-in accounts may buy. Guests earn via welcome/daily/rewarded only; letting
     * them pay for credits leaves the balance on an id the auth service will expire, and letting
     * a signed-in user claim an abandoned guest's purchase was the lever a logout-login cycle
     * used to farm welcome grants. See {@link #requireSignedIn}.
     *
     * <p>The caller is now the owner by construction. Play's obfuscated external account id is
     * still consulted as a sanity check — if present it must equal the caller — but the fallback
     * to "whoever is calling" is intentional, because the caller is the signed-in subject the
     * {@code purchases.products} verification just succeeded for.
     *
     * <p>Never throws CONFLICT for an already-granted token. It used to, and the client read that
     * as "the credits exist" and consumed the purchase — so a redelivered purchase belonging to a
     * previous identity was spent and could never be redelivered again. The response now reports
     * the state and lets the client decide.
     */
    // Deliberately NOT @Transactional. Verifying with Play is a network round trip, and holding
    // a pooled connection across it — then a row lock, once the account is locked — is how a slow
    // dependency stalls the whole application. The write below owns its own short transaction.
    public PurchaseOutcome purchaseCredits(String callerId, String purchaseToken, String productId, String ip) {
        requireSignedIn(callerId);

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
            ledgerWriter.recordVerificationFailureIfFirst(callerId, purchaseToken, productId, ip,
                    BillingChannel.PLAY);
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Google Play could not verify this purchase.");
        }

        // If Play reports an obfuscated external account id, it must match the caller — a signed-in
        // user cannot pay with one account and credit another. When Play reports none (older client
        // builds), the caller is already the only reasonable owner because the token verified under
        // the authenticated session.
        final String playOwner = ownerFromPlay(result.accountId()).orElse(callerId);
        if (!playOwner.equals(callerId)) {
            log.warn("Play purchase owner {} differs from caller {} for product {}; refusing.",
                    playOwner, callerId, productId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This purchase was bound to a different account.");
        }

        final int balanceAfter;
        try {
            balanceAfter = ledgerWriter.grantPurchase(
                    callerId, purchaseToken, productId, result.orderId(), creditsToAdd, ip,
                    BillingChannel.PLAY);
        } catch (DataIntegrityViolationException dup) {
            // The RTDN webhook won the race on uq(user_id, "purchase:<token>") in credit_ledger.
            // The credits are already on the account — read the current balance and return the
            // same shape the "already granted" early-exit above uses, so the client sees a success
            // outcome instead of a 500 "buy failed" toast.
            //
            // The catch MUST live out here, not inside grantPurchase: Hibernate marks the
            // transaction rollback-only on the constraint violation, so swallowing it inside
            // @Transactional makes Spring throw UnexpectedRollbackException at commit time.
            // Keeping the exception on the way out lets Spring roll back cleanly.
            final int callerBalance = getBalance(callerId, ip);
            log.info("Purchase {} was already granted (concurrent writer won); caller={} balance={}",
                    purchaseToken, callerId, callerBalance);
            return new PurchaseOutcome(callerBalance, 0, true, true);
        }

        log.info("Credits purchased: user={} product={} +{} balance={}",
                callerId, productId, creditsToAdd, balanceAfter);
        return new PurchaseOutcome(balanceAfter, creditsToAdd, false, true);
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
        // No caller to fall back on here, so an absent or malformed account id means this
        // delivery cannot be attributed and is left for the client's own redemption.
        var owner = ownerFromPlay(result.accountId());
        if (owner.isEmpty()) {
            log.warn("RTDN purchase has no usable obfuscated account id; leaving it for the client to redeem.");
            return;
        }

        final String userId = owner.get();

        // Mirror the user-initiated purchase policy: guests may not buy. A guest's Play purchase
        // that reaches the RTDN path means the Android client allowed a buy from an anonymous
        // session; the server refuses it rather than silently land credits on an id the auth
        // service will purge. The Pub/Sub delivery is still acked so this does not retry forever;
        // the audit trail is in the log, and support can refund manually. isGuest is cached, so
        // repeated deliveries do not hammer the auth service.
        Boolean isGuest = authInternal.isGuest(userId).orElse(null);
        if (isGuest == null) {
            log.warn("RTDN purchase from userId={} skipped: could not confirm account type with auth.", userId);
            return;
        }
        if (isGuest) {
            log.warn("RTDN purchase from userId={} refused: guest accounts may not buy credits.", userId);
            return;
        }

        try {
            ledgerWriter.grantPurchase(userId, purchaseToken, productId, result.orderId(), credits, null,
                    BillingChannel.PLAY);
        } catch (DataIntegrityViolationException dup) {
            // The client's /purchase call won the race on uq(user_id, "purchase:<token>") first.
            // The credits are on the owner; ack this RTDN so Pub/Sub stops redelivering.
            // Caught here, not inside grantPurchase, so Hibernate's rollback-only mark does not
            // turn into UnexpectedRollbackException. See grantPurchase's javadoc.
            log.info("RTDN purchase was already granted by a concurrent writer; userId={} product={}",
                    userId, productId);
            return;
        }

        log.info("Reconciled unredeemed purchase from RTDN: userId={} product={} +{}",
                userId, productId, credits);
    }

    /**
     * Claws back a refunded/voided purchase's credits, exactly once, through either channel.
     *
     * <p>Idempotent on the audit row's {@code (token_hash, CREDITS_REVOKED)} unique constraint
     * and on the ledger's {@code (user_id, "revoke:<token>")} key, so RTDN redeliveries and
     * nightly-sweep passes converge on one revocation. Balance may go negative when a user has
     * already spent part of the pack they are now refunded.
     *
     * <p>The {@code channel} is the provider initiating the revocation (PLAY for a Google void,
     * RAZORPAY for a Razorpay refund). It is recorded on the clawback row so reporting can split
     * refunds by provider.
     *
     * @return true when this call actually revoked something, so a caller can report what it did
     */
    @Transactional
    public boolean revokeCreditsForToken(String purchaseToken, BillingChannel channel) {
        if (purchaseToken == null || purchaseToken.isBlank()) return false;
        if (purchaseAuditLogRepository.existsByTokenHashAndStatus(PurchaseTokens.hash(purchaseToken), PurchaseStatus.CREDITS_REVOKED)) {
            return false; // already handled (webhook delivery is at-least-once)
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
                PurchaseTokens.ledgerKey("revoke", purchaseToken), null, channel);
        ledgerWriter.saveAudit(granted.getUserId(), purchaseToken, granted.getOrderId(), granted.getProductId(),
                PurchaseStatus.CREDITS_REVOKED, null, null, channel);
        log.info("Credits revoked ({}): userId={} product={} -{} balance={}",
                channel == null ? "unknown" : channel.code(),
                granted.getUserId(), granted.getProductId(), amount, account.getBalance());
        return true;
    }

    // ── internal helpers ──────────────────────────────────────────────────────────


    /**
     * The account key for a Play-reported obfuscated external account id.
     *
     * <p>The subject is attached by the client, which makes it the one identifier here that does
     * not come from a verified token: anything outside the shape auth issues is refused, so a
     * crafted value cannot become an account key, a path segment in a server-to-server call, or
     * a log line of its own choosing.
     *
     * @return empty when Play reported no account id, or one this service would not issue
     */
    private java.util.Optional<String> ownerFromPlay(String playAccountId) {
        if (playAccountId == null || playAccountId.isBlank()) return java.util.Optional.empty();
        if (!Subjects.isValid(playAccountId)) {
            log.warn("Play reported an obfuscated account id that is not a valid subject; ignoring it.");
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(playAccountId);
    }

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

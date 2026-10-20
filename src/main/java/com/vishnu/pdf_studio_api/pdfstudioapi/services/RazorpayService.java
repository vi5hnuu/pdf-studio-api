package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.RazorpayProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.BillingChannel;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PurchaseStatus;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditAccount;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditAccountRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.PurchaseAuditLogRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.security.Subjects;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.PurchaseTokens;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The web INAPP credit flow, in three parts: create a Razorpay order, verify Checkout's success
 * handler and grant credits, and process the webhook delivery that is the source of truth when
 * the browser flow does not reach us.
 *
 * <p>The client-side verify path and the webhook race on the same payment, the same way the Play
 * client's redemption and the Play RTDN race on the same purchase; see
 * {@link CreditLedgerWriter#grantPurchase}. The DB's {@code uq(user_id, idempotency_key)} on
 * {@code credit_ledger} keeps the grant single — one write wins and the loser's
 * {@link DataIntegrityViolationException} is caught in a non-transactional context so the loser
 * also returns a success outcome. Both paths use the same key: {@code razorpay-payment:<paymentId>}.
 *
 * <p>Owner identity is taken from the {@code notes.user_id} attached when the order was created,
 * not from the webhook body — Razorpay stores notes server-side at order creation, so a tampered
 * client cannot shift credits to another account.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RazorpayService {

    private final RazorpayProperties properties;
    private final CreditAccountService accountService;
    private final CreditAccountRepository accountRepository;
    private final CreditLedgerWriter ledgerWriter;
    private final CreditsService creditsService;
    private final ObjectMapper mapper;

    /** Lazily initialised: the SDK client is only built when the configuration is complete. */
    private volatile RazorpayClient client;

    @PostConstruct
    void init() throws RazorpayException {
        if (properties.isOrderingEnabled()) {
            this.client = new RazorpayClient(properties.getKeyId(), properties.getKeySecret());
        }
    }

    // ── 1. Create order ─────────────────────────────────────────────────────────

    /**
     * Creates a Razorpay Order for a credit pack and returns the public shape the browser needs
     * to open Checkout.
     *
     * <p>The pack catalogue, including the price in paise, is owned by this service — a client
     * that could tell the server "this pack is 10 000 credits" is a client that can mint credits.
     * The order's {@code notes} carry the owner subject, product id and credit count so the webhook
     * can finalise without needing a separate server-side state read.
     */
    public OrderDraft createOrder(String ownerSubject, String productId) {
        if (!properties.isOrderingEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Razorpay checkout is not configured on this deployment.");
        }
        if (!Subjects.isValid(ownerSubject)) {
            // A principal name shaped by auth, not supplied by the body — this is defence in depth.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid caller identity.");
        }
        // Only signed-in accounts may pay. Letting a guest buy strands credits on an id the
        // auth service will expire, and used to be the lever a logout-login cycle used to farm
        // welcome grants.
        creditsService.requireSignedIn(ownerSubject);
        RazorpayProperties.Pack pack = properties.packFor(productId);
        if (pack == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown product: " + productId);
        }

        JSONObject request = new JSONObject();
        request.put("amount", pack.getAmountPaise());
        request.put("currency", "INR");
        // A short local reference — Razorpay echoes it back and the dashboard shows it.
        request.put("receipt", "credits-" + System.currentTimeMillis());

        JSONObject notes = new JSONObject();
        notes.put("user_id", ownerSubject);
        notes.put("product_id", productId);
        notes.put("credits", pack.getCredits());
        request.put("notes", notes);

        try {
            Order order = client.orders.create(request);
            String orderId = order.get("id");
            log.info("Razorpay order created: id={} userId={} product={} amount={} credits={}",
                    orderId, ownerSubject, productId, pack.getAmountPaise(), pack.getCredits());
            return new OrderDraft(orderId, properties.getKeyId(), pack.getAmountPaise(),
                    "INR", pack.getCredits(), productId);
        } catch (RazorpayException gatewayFailed) {
            // The user is waiting at a Buy button; a dependency failure here is a 502, not a 500.
            log.error("Razorpay order creation failed for user={} product={}: {}",
                    ownerSubject, productId, gatewayFailed.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not reach Razorpay. Please try again.");
        }
    }

    // ── 2. Client verify ────────────────────────────────────────────────────────

    /**
     * Verifies Checkout's success payload and grants credits.
     *
     * <p>Looks up the Razorpay order to recover the owner, product and credit count — the browser
     * supplies only the three ids and the signature, so trusting it for amount or recipient would
     * let a client claim credits they did not pay for. If the signature matches the order says
     * one thing: this specific payment belongs to this specific order.
     */
    public PurchaseOutcome verifyAndGrant(String callerSubject, String orderId,
                                          String paymentId, String providedSignature, String ip) {
        if (!properties.isOrderingEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Razorpay checkout is not configured on this deployment.");
        }
        creditsService.requireSignedIn(callerSubject);
        if (orderId == null || paymentId == null || providedSignature == null
                || orderId.isBlank() || paymentId.isBlank() || providedSignature.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "orderId, paymentId and signature are all required.");
        }

        if (!RazorpaySignatures.verifyPayment(orderId, paymentId, providedSignature,
                properties.getKeySecret())) {
            // Record the failed attempt against the token the client sent, so a crafted request
            // leaves an audit trail and a repeat attempt does not fill the table.
            ledgerWriter.recordVerificationFailureIfFirst(callerSubject, paymentId, null, ip,
                    BillingChannel.RAZORPAY);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Payment signature could not be verified.");
        }

        OrderFacts facts = fetchOrderFacts(orderId);
        // The caller must be the account the order was created for. A lost session should not let
        // a different signed-in principal claim a paid-for pack. Owner equals caller for every
        // normal flow — the equality is the check.
        if (!facts.ownerSubject.equals(callerSubject)) {
            log.warn("Razorpay /verify called by a different user than the order's owner. caller={} owner={} order={}",
                    callerSubject, facts.ownerSubject, orderId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This purchase belongs to a different account.");
        }

        // Defence in depth over the HMAC alone: a leaked signature pair for a payment that was
        // later refunded still signs, so pull the payment from Razorpay and confirm it is really
        // captured and really charges the pack's price before granting. The HMAC guarantees the
        // ids are coherent; this guarantees the money arrived and nothing clawed it back.
        confirmCapturedAmount(paymentId, facts);

        try {
            return grant(facts.ownerSubject, paymentId, facts.productId, orderId, facts.credits, ip);
        } catch (ResponseStatusException raceLoser) {
            if (raceLoser.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                // The webhook got there first on the audit-row race. The credits exist on the
                // owner's account; return the "already granted" shape so the browser treats it as
                // success instead of a 409.
                int balance = accountRepository.findById(facts.ownerSubject)
                        .map(a -> a.getBalance()).orElse(0);
                log.info("Razorpay /verify saw an already-granted payment: paymentId={}", paymentId);
                return new PurchaseOutcome(balance, 0, true);
            }
            throw raceLoser;
        }
    }

    private void confirmCapturedAmount(String paymentId, OrderFacts facts) {
        try {
            var payment = client.payments.fetch(paymentId);
            String status = payment.get("status");
            int amount = payment.get("amount") instanceof Number n ? n.intValue() : -1;
            if (!"captured".equals(status)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This payment is not captured.");
            }
            RazorpayProperties.Pack pack = properties.packFor(facts.productId);
            if (pack == null || amount != pack.getAmountPaise()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Payment amount does not match the pack price.");
            }
        } catch (RazorpayException unreachable) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not confirm this payment with Razorpay. Please try again.");
        }
    }

    // ── 3. Webhook ──────────────────────────────────────────────────────────────

    /**
     * Dispatches a verified-signature webhook delivery to the right path by event type.
     *
     * <p>Signature verification happens on the controller — this method accepts the parsed JSON
     * so testing does not need the HMAC. Delivery is at-least-once and may re-run after a 2xx;
     * the ledger's uq(user_id, idempotency_key) keeps the grant single either way.
     */
    public void handleWebhook(JsonNode event) {
        String type = event.path("event").asText("");
        switch (type) {
            case "payment.captured" -> onPaymentCaptured(event);
            case "payment.failed" -> onPaymentFailed(event);
            case "refund.processed", "refund.created" -> onRefundProcessed(event);
            default -> log.info("Razorpay webhook {} accepted and ignored (not a subscribed type).", type);
        }
    }

    private void onPaymentCaptured(JsonNode event) {
        JsonNode payment = event.at("/payload/payment/entity");
        if (payment.isMissingNode()) {
            log.warn("Razorpay payment.captured webhook had no payload/payment/entity.");
            return;
        }
        String paymentId = payment.path("id").asText("");
        String orderId = payment.path("order_id").asText("");
        String status = payment.path("status").asText("");
        long amount = payment.path("amount").asLong(0);
        String ownerSubject = payment.at("/notes/user_id").asText("");
        String productId = payment.at("/notes/product_id").asText("");
        int credits = payment.at("/notes/credits").asInt(0);

        if (paymentId.isBlank() || orderId.isBlank() || !Subjects.isValid(ownerSubject)
                || productId.isBlank() || credits <= 0) {
            log.warn("Razorpay payment.captured dropped: incomplete payload (paymentId={}, orderId={}, owner={}, product={}, credits={}).",
                    paymentId, orderId, ownerSubject, productId, credits);
            return;
        }

        // The event name is "payment.captured" but Razorpay's own notes say the body is the
        // source of truth; a webhook replay from an older event does not get special-cased, so
        // check the status directly. "authorized" without "captured" is money held, not taken.
        if (!"captured".equals(status)) {
            log.warn("Razorpay payment.captured for a payment not in captured status: paymentId={} status={}",
                    paymentId, status);
            return;
        }

        // Only the server's catalogue is trusted for pricing and pack size. A webhook that claims
        // credits the server does not sell, or asks for less money than the pack costs, is refused.
        RazorpayProperties.Pack pack = properties.packFor(productId);
        if (pack == null || pack.getCredits() != credits || pack.getAmountPaise() != amount) {
            log.warn("Razorpay payment.captured mismatches server catalogue: product={} notesCredits={} notesAmount={} pack={}",
                    productId, credits, amount,
                    pack == null ? null : (pack.getCredits() + "@" + pack.getAmountPaise()));
            return;
        }

        try {
            PurchaseOutcome outcome = grant(ownerSubject, paymentId, productId, orderId, credits, null);
            log.info("Razorpay webhook granted purchase: user={} product={} +{} balanceAfter={}",
                    ownerSubject, productId, outcome.creditsGranted(), outcome.balance());
        } catch (ResponseStatusException raceLoser) {
            // The /verify path got there first; grant() already captured the balance. Nothing to do.
            log.info("Razorpay webhook saw an already-granted payment: payment={}", paymentId);
        }
    }

    private void onPaymentFailed(JsonNode event) {
        JsonNode payment = event.at("/payload/payment/entity");
        String paymentId = payment.path("id").asText("");
        String ownerSubject = payment.at("/notes/user_id").asText("");
        String productId = payment.at("/notes/product_id").asText("");
        if (paymentId.isBlank() || !Subjects.isValid(ownerSubject)) {
            log.info("Razorpay payment.failed received without usable notes.user_id; nothing to audit.");
            return;
        }
        ledgerWriter.recordVerificationFailureIfFirst(ownerSubject, paymentId,
                productId.isBlank() ? null : productId, null, BillingChannel.RAZORPAY);
    }

    private void onRefundProcessed(JsonNode event) {
        JsonNode refund = event.at("/payload/refund/entity");
        String paymentId = refund.path("payment_id").asText("");
        if (paymentId.isBlank()) {
            log.warn("Razorpay refund webhook had no payment_id; nothing to revoke.");
            return;
        }
        // Shares a transactional revoke path with the Play webhook — the payment id travels in
        // the same purchase_token column, and the only difference between the two refund paths
        // is the channel that lands on the clawback rows.
        boolean revoked = creditsService.revokeCreditsForToken(paymentId, BillingChannel.RAZORPAY);
        if (!revoked) {
            log.info("Razorpay refund for payment {} did not revoke anything (not ours or already revoked).",
                    paymentId);
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    /**
     * The grant itself, around the same race pattern Play uses. Caller is non-transactional so
     * {@link DataIntegrityViolationException} propagates out cleanly instead of poisoning the TX.
     */
    private PurchaseOutcome grant(String ownerSubject, String paymentId, String productId,
                                  String orderId, int credits, String ip) {
        accountService.ensure(ownerSubject, ip);
        int balance;
        try {
            balance = ledgerWriter.grantPurchase(ownerSubject, paymentId, productId, orderId,
                    credits, ip, BillingChannel.RAZORPAY);
        } catch (DataIntegrityViolationException concurrentWriterWon) {
            CreditAccount after = accountRepository.findById(ownerSubject).orElse(null);
            balance = after == null ? 0 : after.getBalance();
            log.info("Razorpay payment {} was already granted (concurrent writer won); balance={}",
                    paymentId, balance);
            return new PurchaseOutcome(balance, 0, true);
        }
        return new PurchaseOutcome(balance, credits, false);
    }

    private OrderFacts fetchOrderFacts(String orderId) {
        try {
            Order order = client.orders.fetch(orderId);
            String ownerSubject = order.get("notes") != null
                    ? ((JSONObject) order.get("notes")).optString("user_id", "")
                    : "";
            String productId = order.get("notes") != null
                    ? ((JSONObject) order.get("notes")).optString("product_id", "")
                    : "";
            int credits = order.get("notes") != null
                    ? ((JSONObject) order.get("notes")).optInt("credits", 0)
                    : 0;
            if (!Subjects.isValid(ownerSubject) || productId.isBlank() || credits <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Order is missing the metadata needed to grant credits.");
            }
            return new OrderFacts(ownerSubject, productId, credits);
        } catch (RazorpayException notReachable) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not confirm this order with Razorpay. Please try again.");
        }
    }

    // ── Returned shapes ────────────────────────────────────────────────────────

    public record OrderDraft(String orderId, String keyId, int amountPaise, String currency,
                             int credits, String productId) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId", orderId);
            m.put("keyId", keyId);
            m.put("amount", amountPaise);
            m.put("currency", currency);
            m.put("credits", credits);
            m.put("productId", productId);
            return m;
        }
    }

    public record PurchaseOutcome(int balance, int creditsGranted, boolean alreadyGranted) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("credits", balance);
            m.put("creditsGranted", creditsGranted);
            m.put("alreadyGranted", alreadyGranted);
            return m;
        }
    }

    private record OrderFacts(String ownerSubject, String productId, int credits) {}
}

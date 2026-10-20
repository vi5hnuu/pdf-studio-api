package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds {@code app.razorpay.*} — the credentials and catalogue for the web INAPP credit flow.
 *
 * <p>A blank credential disables the matching path cleanly instead of failing on first use. The
 * order endpoint checks {@link #isOrderingEnabled} and the webhook checks {@link #isWebhookEnabled}
 * on every request, so a half-configured deployment (JWKS up, Razorpay keys missing) still serves
 * everything that does not involve the gateway.
 *
 * <p>Prices are stored in paise so arithmetic stays integral; INR is a decimal currency and
 * {@code double} on billing values is how off-by-a-rupee bugs happen.
 */
@Component
@ConfigurationProperties(prefix = "app.razorpay")
@Getter
@Setter
@Slf4j
public class RazorpayProperties {

    /**
     * The public key id (e.g. {@code rzp_live_...} or {@code rzp_test_...}). Returned to the
     * browser so Checkout.js can open the dialog, so this is safe to expose; the paired secret
     * stays here.
     */
    private String keyId = "";

    /** The private API key secret. Signs the HMAC on Checkout's success handler. Never leaves. */
    private String keySecret = "";

    /**
     * The webhook secret, configured on the Razorpay dashboard webhook. Signs {@code
     * X-Razorpay-Signature} on inbound webhook deliveries. Distinct from {@link #keySecret}.
     */
    private String webhookSecret = "";

    /**
     * The product catalogue. Keyed by Play SKU so the web and the app share one product space
     * — a user who buys {@code pdfcraft_credits_10} on either tier gets the same 10 credits on
     * the same account. Values are in paise so the price never leaves integer arithmetic.
     *
     * <p>Not a public contract: the Spring binder populates this map from indexed properties
     * (see application.properties), so adding a product is a one-line config change. Each entry
     * also sets how many credits the pack is worth, which the server must own — a client that
     * can tell the server "this pack is 1000 credits" is a client that can mint credits.
     */
    private Map<String, Pack> packs = new LinkedHashMap<>();

    public Pack packFor(String productId) {
        return productId == null ? null : packs.get(productId);
    }

    /** Whether an order can be created (credentials present and at least one pack configured). */
    public boolean isOrderingEnabled() {
        return !keyId.isBlank() && !keySecret.isBlank() && !packs.isEmpty();
    }

    /** Whether a webhook can be verified (webhook secret present). */
    public boolean isWebhookEnabled() {
        return !webhookSecret.isBlank();
    }

    @PostConstruct
    void logStatus() {
        if (!isOrderingEnabled()) {
            log.info("Razorpay ordering is disabled: credentials or pack catalogue missing.");
        } else {
            log.info("Razorpay ordering enabled with keyId={} and {} pack(s).",
                    maskKeyId(keyId), packs.size());
        }
        if (!isWebhookEnabled()) {
            log.info("Razorpay webhook verification is disabled: webhook secret missing.");
        }
    }

    private static String maskKeyId(String keyId) {
        if (keyId == null || keyId.length() < 10) return "***";
        return keyId.substring(0, 10) + "…";
    }

    @Getter
    @Setter
    public static class Pack {
        /** INR amount in paise. 5000 means ₹50.00. */
        private int amountPaise;
        /** Credits granted on successful payment. */
        private int credits;
    }
}

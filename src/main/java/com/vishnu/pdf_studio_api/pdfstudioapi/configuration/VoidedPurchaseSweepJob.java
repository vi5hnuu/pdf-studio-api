package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.BillingChannel;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.CreditsService;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.PlayStoreVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Claws back refunds that the real-time notifications missed.
 *
 * <p>A voided purchase reaches this service one way only: a Play RTDN pushed through Pub/Sub. That
 * is fine until it is not — Pub/Sub stops retrying after its retention window, so a refund arriving
 * while this service is down, or while the webhook is misconfigured, is dropped and the credits
 * stay spendable. Nothing ever looked again, so the loss was silent and permanent.
 *
 * <p>This sweeps Google's own voided-purchase list instead of trusting delivery. It is a backstop,
 * not the primary path: the webhook still does the work in real time, and
 * {@link CreditsService#revokeCreditsForToken} is idempotent, so re-offering a token that was
 * already clawed back costs one lookup and changes nothing.
 *
 * <p>The window is deliberately wider than the gap it covers. Google retains voided purchases for
 * 30 days and re-examining a token is free, so looking too far back is cheap and looking not far
 * enough loses money.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoidedPurchaseSweepJob {

    private final PlayStoreVerifier playStoreVerifier;
    private final CreditsService creditsService;

    /** How far back each sweep looks. */
    @Value("${app.play.voided-sweep-days:7}")
    private int sweepDays;

    @Scheduled(cron = "${app.play.voided-sweep-cron:0 45 3 * * *}")
    public void sweep() {
        final Instant since = Instant.now().minus(Duration.ofDays(sweepDays));
        final List<String> tokens = playStoreVerifier.listVoidedPurchaseTokens(since);
        if (tokens.isEmpty()) return;

        int revoked = 0;
        for (String token : tokens) {
            try {
                // Each in its own transaction, so one bad token cannot abandon the rest.
                if (creditsService.revokeCreditsForToken(token, BillingChannel.PLAY)) revoked++;
            } catch (Exception e) {
                log.error("Voided-purchase sweep could not revoke a token: {}", e.getMessage());
            }
        }
        if (revoked > 0) {
            log.warn("Voided-purchase sweep clawed back {} purchase(s) the webhook had missed, "
                    + "out of {} voided in the last {} day(s).", revoked, tokens.size(), sweepDays);
        }
    }
}

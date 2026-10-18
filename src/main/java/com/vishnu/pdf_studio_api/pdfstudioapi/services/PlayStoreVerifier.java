package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.AndroidPublisherScopes;
import com.google.api.services.androidpublisher.model.ProductPurchase;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies Google Play INAPP (one-time) purchases with the Play Developer API.
 *
 * <p>The {@link AndroidPublisher} client is built lazily from a service-account key and
 * cached. If no key is configured (e.g. local dev), verification returns invalid rather
 * than throwing, so the credit purchase endpoint degrades safely.
 */
@Service
@Slf4j
public class PlayStoreVerifier {

    private final String serviceAccountKeyPath;
    private final String packageName;
    private volatile AndroidPublisher publisher;

    public PlayStoreVerifier(@Value("${app.play.service-account-key-path:}") String serviceAccountKeyPath,
                             @Value("${app.play.package-name}") String packageName) {
        this.serviceAccountKeyPath = serviceAccountKeyPath;
        this.packageName = packageName;
    }

    /** Verifies a one-time product purchase token. */
    public InAppPurchaseResult verifyInAppPurchase(String productId, String purchaseToken) {
        final AndroidPublisher api = publisher();
        if (api == null) {
            log.warn("Play verification skipped — no service account key configured.");
            return new InAppPurchaseResult(false, null, null);
        }
        try {
            ProductPurchase purchase = api.purchases().products()
                    .get(packageName, productId, purchaseToken).execute();
            // purchaseState: 0 = purchased, 1 = cancelled, 2 = pending.
            boolean valid = purchase.getPurchaseState() != null && purchase.getPurchaseState() == 0;
            return new InAppPurchaseResult(valid, purchase.getOrderId(),
                    purchase.getObfuscatedExternalAccountId());
        } catch (Exception e) {
            log.error("Play verification error for product={}: {}", productId, e.getMessage());
            return new InAppPurchaseResult(false, null, null);
        }
    }

    /**
     * Purchase tokens Google has voided (refund, chargeback, developer-issued refund) since
     * [since].
     *
     * <p>A safety net for the real-time notifications, which are the only thing that claws credits
     * back today. Pub/Sub gives up after its retention window, so a refund that arrives while this
     * service is down is dropped and the credits stay spent — silently, because nothing ever looks
     * again. Google keeps the voided list for 30 days, which is what makes a sweep possible.
     *
     * @return tokens to revoke, oldest first; empty when Play cannot be reached, so a failed
     *         sweep is a no-op rather than a false "nothing was refunded"
     */
    public List<String> listVoidedPurchaseTokens(Instant since) {
        final AndroidPublisher api = publisher();
        if (api == null) {
            log.warn("Voided-purchase sweep skipped — no service account key configured.");
            return List.of();
        }
        final List<String> tokens = new ArrayList<>();
        try {
            String pageToken = null;
            do {
                var request = api.purchases().voidedpurchases()
                        .list(packageName)
                        .setStartTime(since.toEpochMilli());
                if (pageToken != null) request.setToken(pageToken);
                var response = request.execute();
                if (response.getVoidedPurchases() != null) {
                    response.getVoidedPurchases().forEach(v -> {
                        if (v.getPurchaseToken() != null) tokens.add(v.getPurchaseToken());
                    });
                }
                pageToken = response.getTokenPagination() == null
                        ? null : response.getTokenPagination().getNextPageToken();
            } while (pageToken != null);
        } catch (Exception e) {
            log.error("Could not list voided purchases: {}", e.getMessage());
            return List.of();
        }
        return tokens;
    }

    private AndroidPublisher publisher() {
        if (publisher != null) return publisher;
        if (serviceAccountKeyPath == null || serviceAccountKeyPath.isBlank()) return null;
        synchronized (this) {
            if (publisher != null) return publisher;
            try (FileInputStream in = new FileInputStream(serviceAccountKeyPath)) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(in)
                        .createScoped(List.of(AndroidPublisherScopes.ANDROIDPUBLISHER));
                publisher = new AndroidPublisher.Builder(
                        GoogleNetHttpTransport.newTrustedTransport(),
                        GsonFactory.getDefaultInstance(),
                        new HttpCredentialsAdapter(credentials))
                        .setApplicationName(packageName)
                        .build();
                log.info("Initialized Play Developer API client.");
                return publisher;
            } catch (Exception e) {
                log.error("Failed to initialize Play Developer API client: {}", e.getMessage());
                return null;
            }
        }
    }

    /**
     * @param valid    whether Google confirmed the purchase
     * @param orderId  Play order id (may be null)
     * @param accountId the obfuscated external account id the client attached at purchase time —
     *                  our userId. This is the only way a server-initiated reconciliation (an RTDN
     *                  for a purchase the client never redeemed) can tell whose account to credit.
     */
    public record InAppPurchaseResult(boolean valid, String orderId, String accountId) {}
}

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
            return new InAppPurchaseResult(false, null);
        }
        try {
            ProductPurchase purchase = api.purchases().products()
                    .get(packageName, productId, purchaseToken).execute();
            // purchaseState: 0 = purchased, 1 = cancelled, 2 = pending.
            boolean valid = purchase.getPurchaseState() != null && purchase.getPurchaseState() == 0;
            return new InAppPurchaseResult(valid, purchase.getOrderId());
        } catch (Exception e) {
            log.error("Play verification error for product={}: {}", productId, e.getMessage());
            return new InAppPurchaseResult(false, null);
        }
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

    /** @param valid whether Google confirmed the purchase; @param orderId Play order id (may be null). */
    public record InAppPurchaseResult(boolean valid, String orderId) {}
}

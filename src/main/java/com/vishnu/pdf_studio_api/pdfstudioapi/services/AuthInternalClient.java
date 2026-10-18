package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.AuthIssuerProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.security.PrincipalKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Optional;

/**
 * Server-to-server calls into the auth service, for the two facts this service cannot know itself:
 * that a guest has paid and must not be purged, and whether an account is a guest at all.
 *
 * <p>The auth service expires guest accounts after a few days of inactivity and a nightly job
 * deletes them along with their refresh tokens. That is right for the anonymous accounts the web
 * tier mints on demand, and wrong for one that has bought credits: this service is never told
 * about the deletion, so the balance stays on a principal that no longer exists, and the next
 * launch silently becomes a different guest with a welcome balance. A guest who paid and then did
 * not open the app for a week lost what they bought.
 *
 * <p>Auth owns the expiry and knows nothing about purchases, so the fact has to travel the other
 * way. Best effort by design: a purchase must never fail because retention could not be recorded.
 * The grant is the thing that matters, and a missed retention only restores the old risk.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthInternalClient {

    private final AuthIssuerProperties authProperties;
    private final ObjectMapper mapper;

    /** Shared secret the auth service checks on its internal endpoints. Retention is skipped without one. */
    @Value("${app.auth.internal-secret:}")
    private String internalSecret;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * Runs retention off the request thread. Deliberately small and deliberately bounded: a
     * queue that grows without limit under a failing dependency is how an out-of-memory error
     * starts, and dropping a best-effort call is the cheaper failure.
     */
    private final ExecutorService retainExecutor = new ThreadPoolExecutor(
            0, 2, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "guest-retention");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /**
     * Clears the guest expiry for the account behind [principalKey].
     *
     * <p>Never on the caller's thread, and never inside a caller's transaction.
     *
     * <p>Off the thread because this is best-effort bookkeeping on the purchase path: the buyer
     * should not wait on the auth service to be told their credits arrived, and a slow dependency
     * should not consume a request thread. It runs on a small bounded pool and is dropped under
     * saturation rather than queued — a missed retention restores an old risk for one account,
     * where an unbounded queue behind a failing dependency is an out-of-memory error.
     *
     * <p>The transaction check is defence in depth. Today every caller is deliberately
     * non-transactional, so this runs immediately; the callers were restructured precisely so
     * that no HTTP call happens while a pooled connection and a {@code SELECT ... FOR UPDATE}
     * row lock are held. Deferring to after commit keeps that guarantee if this is ever called
     * from somewhere transactional, and stops a retention being recorded for a purchase that
     * then rolls back.
     *
     * @param principalKey the {@code alias:sub} this service keys accounts by
     */
    public void retain(String principalKey) {
        if (internalSecret == null || internalSecret.isBlank()) return;

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitRetain(principalKey);
                }
            });
            return;
        }
        submitRetain(principalKey);
    }

    private void submitRetain(String principalKey) {
        try {
            retainExecutor.execute(() -> retainNow(principalKey));
        } catch (RejectedExecutionException saturated) {
            log.warn("Guest retention for {} dropped: retention queue is full", principalKey);
        }
    }

    /** The blocking call itself, always off the request thread. */
    private void retainNow(String principalKey) {

        final String alias = PrincipalKey.aliasOf(principalKey);
        final String subject = PrincipalKey.subjectOf(principalKey);
        if (alias == null || subject == null || subject.isBlank()) return;

        final String baseUrl = baseUrlFor(alias);
        if (baseUrl == null) {
            log.warn("No auth base URL configured for alias '{}'; guest retention skipped.", alias);
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/internal/users/" + subject + "/retain"))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-Internal-Secret", internalSecret)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Guest retention for {} returned HTTP {}", principalKey, response.statusCode());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Guest retention for {} was interrupted", principalKey);
        } catch (Exception e) {
            log.warn("Guest retention for {} failed: {}", principalKey, e.getMessage());
        }
    }

    /**
     * Whether the account behind [principalKey] is an anonymous guest.
     *
     * <p>Decides whether a purchase may be reclaimed by a different session. Empty when the answer
     * cannot be obtained, and the caller must read that as "do not transfer": guessing wrong here
     * hands one person's purchase to another.
     */
    public Optional<Boolean> isGuest(String principalKey) {
        if (internalSecret == null || internalSecret.isBlank()) return Optional.empty();

        final String alias = PrincipalKey.aliasOf(principalKey);
        final String subject = PrincipalKey.subjectOf(principalKey);
        if (alias == null || subject == null || subject.isBlank()) return Optional.empty();

        final String baseUrl = baseUrlFor(alias);
        if (baseUrl == null) return Optional.empty();

        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/internal/users/" + subject))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-Internal-Secret", internalSecret)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return Optional.empty();
            JsonNode type = mapper.readTree(response.body()).at("/data/accountType");
            if (type.isMissingNode() || type.isNull()) return Optional.empty();
            return Optional.of("GUEST".equalsIgnoreCase(type.asText()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not read account type for {}: {}", principalKey, e.getMessage());
            return Optional.empty();
        }
    }

    /** The configured base URL of the auth instance that issued this alias. */
    private String baseUrlFor(String alias) {
        return authProperties.getIssuers().stream()
                .filter(i -> alias.equals(i.getAlias()))
                .map(AuthIssuerProperties.Issuer::getInternalBaseUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse(null);
    }
}

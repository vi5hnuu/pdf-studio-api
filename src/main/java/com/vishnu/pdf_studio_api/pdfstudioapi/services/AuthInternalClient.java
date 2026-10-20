package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.AuthIssuerProperties;
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
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
     * Clears the guest expiry for the account behind [subject].
     *
     * <p>Never on the caller's thread, and never inside a caller's transaction. Off the thread
     * because this is best-effort bookkeeping on the purchase path: the buyer should not wait on
     * the auth service to be told their credits arrived, and a slow dependency should not consume
     * a request thread. It runs on a small bounded pool and is dropped under saturation rather
     * than queued — a missed retention restores an old risk for one account, where an unbounded
     * queue behind a failing dependency is an out-of-memory error.
     *
     * <p>The transaction check is defence in depth. Today every caller is deliberately
     * non-transactional, so this runs immediately; the callers were restructured precisely so
     * that no HTTP call happens while a pooled connection and a {@code SELECT ... FOR UPDATE}
     * row lock are held. Deferring to after commit keeps that guarantee if this is ever called
     * from somewhere transactional, and stops a retention being recorded for a purchase that
     * then rolls back.
     */
    public void retain(String subject) {
        if (internalSecret == null || internalSecret.isBlank()) return;

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitRetain(subject);
                }
            });
            return;
        }
        submitRetain(subject);
    }

    private void submitRetain(String subject) {
        try {
            retainExecutor.execute(() -> retainNow(subject));
        } catch (RejectedExecutionException saturated) {
            log.warn("Guest retention for {} dropped: retention queue is full", subject);
        }
    }

    /** The blocking call itself, always off the request thread. */
    private void retainNow(String subject) {
        if (subject == null || subject.isBlank()) return;

        final String baseUrl = authProperties.getInternalBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("No auth internal base URL configured; guest retention skipped.");
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
                log.warn("Guest retention for {} returned HTTP {}", subject, response.statusCode());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Guest retention for {} was interrupted", subject);
        } catch (Exception e) {
            log.warn("Guest retention for {} failed: {}", subject, e.getMessage());
        }
    }

    /**
     * Whether the account behind [subject] is an anonymous guest.
     *
     * <p>Decides whether a purchase may be reclaimed by a different session. Empty when the answer
     * cannot be obtained, and the caller must read that as "do not transfer": guessing wrong here
     * hands one person's purchase to another.
     */
    public Optional<Boolean> isGuest(String subject) {
        if (internalSecret == null || internalSecret.isBlank()) return Optional.empty();
        if (subject == null || subject.isBlank()) return Optional.empty();

        final String baseUrl = authProperties.getInternalBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return Optional.empty();

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
            log.warn("Could not read account type for {}: {}", subject, e.getMessage());
            return Optional.empty();
        }
    }
}

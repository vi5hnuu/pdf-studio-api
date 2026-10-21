package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.AuthIssuerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Server-to-server call into the auth service for the one fact this service cannot know itself:
 * whether an account is a guest at all.
 *
 * <p>Guest retention used to live here too — a guest who paid had to outlive the auth service's
 * inactivity-based purge, or their balance would be stranded on a principal that no longer
 * existed. That path is gone because guests are no longer permitted to buy credits (see
 * {@code CreditsService.requireSignedIn}); real signed-in accounts are not purged for
 * inactivity, so retention is a no-op by construction.
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

    /** How long a "this is a real signed-in account" answer is reused. */
    private static final Duration SIGNED_IN_TTL = Duration.ofSeconds(60);

    /** How long an "auth could not tell us" answer is reused. */
    private static final Duration UNKNOWN_TTL = Duration.ofSeconds(5);

    /**
     * Cache of {@code isGuest} answers, with a different lifetime for each answer.
     *
     * <p>A single TTL was wrong here, because the three answers do not age the same way.
     *
     * <ul>
     *   <li><b>A guest answer is never cached.</b> {@code guest → signed-in} is a one-way
     *       transition, and it is exactly the one the app asks people to make when it refuses
     *       them a purchase: convert, then buy. Serving a stale "still a guest" for even a few
     *       seconds refuses a paying customer and tells them to do the thing they just did. A
     *       refused buy is also rare and already draws on the tight rate-limit bucket, so the
     *       round trip costs nothing worth keeping.
     *   <li><b>A signed-in answer is cached for {@link #SIGNED_IN_TTL}.</b> This is the hot path
     *       the cache exists for — RTDN arriving in bursts, and repeat buys by the same user —
     *       and it cannot go stale in a harmful direction, because a real account never becomes
     *       a guest.
     *   <li><b>An unknown answer is cached for {@link #UNKNOWN_TTL}.</b> Long enough to stop a
     *       burst hammering an auth service that is already struggling, short enough that
     *       recovery is quick. Callers fail closed on unknown meanwhile.
     * </ul>
     */
    private final Cache<String, Optional<Boolean>> isGuestCache = newIsGuestCache(Ticker.systemTicker());

    /**
     * Builds the cache described above.
     *
     * <p>A factory rather than an inline field initialiser so a test can drive the real policy
     * with a fake clock instead of waiting out a minute of wall time — the lifetimes are the
     * whole behaviour here, and a copy of the policy in a test would prove nothing about this one.
     */
    static Cache<String, Optional<Boolean>> newIsGuestCache(Ticker ticker) {
        return Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfter(new Expiry<String, Optional<Boolean>>() {
                    @Override
                    public long expireAfterCreate(String key, Optional<Boolean> answer, long currentTime) {
                        return ttlFor(answer).toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, Optional<Boolean> answer,
                                                  long currentTime, long currentDuration) {
                        return ttlFor(answer).toNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, Optional<Boolean> answer,
                                                long currentTime, long currentDuration) {
                        return currentDuration; // reading does not extend the life of an answer
                    }
                })
                .maximumSize(10_000)
                .build();
    }

    /** Zero for a guest, so the next call asks again. See {@link #isGuestCache}. */
    private static Duration ttlFor(Optional<Boolean> answer) {
        if (answer.isEmpty()) return UNKNOWN_TTL;
        return answer.get() ? Duration.ZERO : SIGNED_IN_TTL;
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
        if (authProperties.getInternalBaseUrl() == null
                || authProperties.getInternalBaseUrl().isBlank()) return Optional.empty();

        return isGuestCache.get(subject, this::fetchIsGuest);
    }

    private Optional<Boolean> fetchIsGuest(String subject) {
        final String baseUrl = authProperties.getInternalBaseUrl();
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

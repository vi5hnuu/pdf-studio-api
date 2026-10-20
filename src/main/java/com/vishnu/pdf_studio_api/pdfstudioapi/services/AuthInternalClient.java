package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

    /**
     * Short-TTL cache of {@code isGuest} answers so a buy that happens seconds after another
     * buy by the same user does not pay a second network round trip to auth, and so a brief
     * auth-service degradation does not stall every purchase thread. 60 s is tight enough that
     * an account-type change (guest → signed-in on first sign-in) is seen quickly without
     * tanking performance on the hot path. Caches both positive and negative results, including
     * the empty "could not determine" so a down auth service does not get hammered.
     */
    private final Cache<String, Optional<Boolean>> isGuestCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build();

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

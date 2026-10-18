package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.AuthIssuerProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.security.PrincipalKey;
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
     * Clears the guest expiry for the account behind [principalKey], if we can work out where to
     * ask. A full account has no expiry, so the call is harmless there and auth ignores it.
     *
     * @param principalKey the {@code alias:sub} this service keys accounts by
     */
    public void retain(String principalKey) {
        if (internalSecret == null || internalSecret.isBlank()) return;

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

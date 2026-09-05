package com.vishnu.pdf_studio_api.pdfstudioapi.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.RateLimitProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.exception.ApiError;
import com.vishnu.pdf_studio_api.pdfstudioapi.security.CurrentUser;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.ClientIp;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Bounds how often one user — and one IP — may invoke the tool endpoints
 * (bucket4j + an in-memory Caffeine store, mirroring the auth service's filter).
 *
 * <p>Both dimensions are checked because either alone is insufficient: a per-user limit is
 * defeated by minting fresh guest accounts (trivial for the web tier), and a per-IP limit alone
 * punishes everyone behind a shared NAT. Render/convert endpoints additionally draw on a much
 * tighter "heavy" bucket.
 *
 * <p>Registered as a plain {@code @Component}, so it runs <em>after</em> Spring Security's chain
 * and the authenticated principal is available. Requests that fail authentication never reach it —
 * they are already rejected with a cheap, local 401.
 */
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    /** Only the tool endpoints are limited; credits, docs and health are not. */
    private static final String[] GUARDED_PREFIXES = {
            "/api/v1/pdf-studio/", "/api/v1/image-studio/"
    };

    /**
     * Tools that rasterise or convert every page. These dominate CPU and memory, so they draw on
     * an additional, much smaller bucket.
     */
    private static final Set<String> HEAVY_TOOLS = Set.of(
            "pdf-to-jpg", "pdf-to-word", "pdf-to-excel", "pdf-to-pptx",
            "compress-pdf", "grayscale-pdf", "remove-blank-pages", "analyze-pdf",
            "optimize-pdf", "repair-pdf", "extract-images", "n-up", "redact-pdf",
            "split-by-size", "image-to-pdf"
    );

    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(15))
            .maximumSize(100_000)
            .build();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) return true;
        String uri = request.getRequestURI();
        for (String prefix : GUARDED_PREFIXES) {
            if (uri.startsWith(prefix)) return false;
        }
        return true;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        final String tool = toolOf(request.getRequestURI());
        final String ip = ClientIp.of(request);
        final String userId = CurrentUser.id();

        // Every configured dimension must have capacity. Checked cheapest-first so a saturated
        // heavy bucket does not needlessly consume the user's general allowance.
        if (HEAVY_TOOLS.contains(tool) && !tryConsume("h|" + key(userId, ip), this::heavyBucket)) {
            reject(response, "You're running heavy tools too quickly. Please wait a moment.");
            return;
        }
        if (userId != null && !tryConsume("u|" + userId, this::userBucket)) {
            reject(response, "Too many requests. Please slow down and try again shortly.");
            return;
        }
        if (!tryConsume("i|" + ip, this::ipBucket)) {
            reject(response, "Too many requests from your network. Please try again shortly.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    // ── buckets ───────────────────────────────────────────────────────────────────

    private boolean tryConsume(String key, java.util.function.Supplier<Bucket> factory) {
        return buckets.get(key, k -> factory.get()).tryConsume(1);
    }

    private Bucket userBucket() {
        return bucket(properties.getUserCapacity(), properties.getUserRefillPerMinute());
    }

    private Bucket ipBucket() {
        return bucket(properties.getIpCapacity(), properties.getIpRefillPerMinute());
    }

    private Bucket heavyBucket() {
        return bucket(properties.getHeavyCapacity(), properties.getHeavyRefillPerMinute());
    }

    private Bucket bucket(int capacity, int refillPerMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.greedy(refillPerMinute, Duration.ofMinutes(1))))
                .build();
    }

    /** Prefers the userId; falls back to IP for the (rare) unauthenticated path. */
    private String key(String userId, String ip) {
        return userId != null ? userId : ip;
    }

    /** Last path segment — the tool id, matching the {@code @ChargeCredits} ids. */
    private String toolOf(String uri) {
        int slash = uri.lastIndexOf('/');
        return slash >= 0 && slash < uri.length() - 1 ? uri.substring(slash + 1) : "";
    }

    /** Responds in the same {@link ApiError} envelope every other failure uses. */
    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiError.of(
                HttpStatus.TOO_MANY_REQUESTS.value(), "RATE_LIMITED", message,
                UUID.randomUUID().toString().substring(0, 8)));
    }
}

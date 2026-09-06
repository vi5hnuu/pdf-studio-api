package com.vishnu.pdf_studio_api.pdfstudioapi.concurrency;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.LoadProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Admission control for expensive operations.
 *
 * <p>A fair semaphore bounds how many heavy tools run at once. A request waits up to
 * {@code app.load.queue-timeout-seconds} for a slot; if none frees up it is rejected with a 503 and
 * a {@code Retry-After} hint. Fairness matters here — without it a steady stream of new arrivals
 * can starve a request that has already been waiting.
 *
 * <p>This is the difference between degrading gracefully under a burst and dying: an unbounded
 * server accepts all 200 Tomcat threads' worth of work and then fails every one of them on heap
 * exhaustion.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HeavyToolLimiter {

    private final LoadProperties properties;
    private Semaphore permits;

    @PostConstruct
    void init() {
        permits = new Semaphore(properties.getMaxConcurrentHeavy(), true); // fair: FIFO, no starvation
        log.info("Heavy-tool concurrency limited to {} (queue timeout {}s)",
                properties.getMaxConcurrentHeavy(), properties.getQueueTimeoutSeconds());
    }

    /**
     * Runs {@code work} while holding a permit, releasing it even if the work throws.
     *
     * @throws ResponseStatusException 503 when no permit becomes available in time
     */
    public <T> T call(ThrowingSupplier<T> work) throws Throwable {
        acquire();
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }

    private void acquire() {
        try {
            if (!permits.tryAcquire(properties.getQueueTimeoutSeconds(), TimeUnit.SECONDS)) {
                log.warn("Heavy-tool limiter saturated — shedding request");
                throw busy();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy();
        }
    }

    /**
     * Builds the 503 carrying {@code Retry-After}.
     *
     * <p>{@code ResponseStatusException.getHeaders()} is read-only in Spring 6, so the header has to
     * be supplied by overriding the accessor — mutating the returned instance throws
     * {@link UnsupportedOperationException}, which would have turned every shed request into a 500.
     */
    private ResponseStatusException busy() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(properties.getRetryAfterSeconds()));
        return new ServerBusyException(headers);
    }

    /** A 503 that can actually carry headers. */
    private static class ServerBusyException extends ResponseStatusException {

        private final transient HttpHeaders headers;

        ServerBusyException(HttpHeaders headers) {
            super(HttpStatus.SERVICE_UNAVAILABLE,
                    "The server is busy processing other files. Please try again in a moment.");
            this.headers = headers;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }

    /** Permits currently free — exposed for tests and diagnostics. */
    public int availablePermits() {
        return permits.availablePermits();
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }
}

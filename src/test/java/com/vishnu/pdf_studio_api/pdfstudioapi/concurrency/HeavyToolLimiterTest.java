package com.vishnu.pdf_studio_api.pdfstudioapi.concurrency;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.LoadProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class HeavyToolLimiterTest {

    private HeavyToolLimiter limiter(int permits, int queueTimeoutSeconds) {
        LoadProperties props = new LoadProperties();
        props.setMaxConcurrentHeavy(permits);
        props.setQueueTimeoutSeconds(queueTimeoutSeconds);
        props.setRetryAfterSeconds(5);
        HeavyToolLimiter limiter = new HeavyToolLimiter(props);
        limiter.init();
        return limiter;
    }

    @Test
    @DisplayName("releases the permit even when the work throws")
    void releasesOnFailure() {
        HeavyToolLimiter limiter = limiter(1, 1);
        assertThrows(IllegalStateException.class, () -> limiter.call(() -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, limiter.availablePermits(), "a failed run must not leak its permit");
    }

    @Test
    @DisplayName("sheds with 503 + Retry-After once saturated instead of piling work on")
    void shedsWhenSaturated() throws Exception {
        HeavyToolLimiter limiter = limiter(1, 1);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // limiter.call declares Throwable (it wraps ProceedingJoinPoint::proceed), which is
            // wider than Callable's Exception — so the holder task handles it itself.
            pool.submit(() -> {
                try {
                    limiter.call(() -> {
                        holding.countDown();
                        release.await();
                        return "done";
                    });
                } catch (Throwable ignored) {
                    // irrelevant here: this task exists only to hold the permit
                }
            });
            assertTrue(holding.await(5, TimeUnit.SECONDS), "first task should hold the only permit");

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> limiter.call(() -> "second"));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
            assertEquals("5", ex.getHeaders().getFirst("Retry-After"));
        } finally {
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("never admits more than the configured number at once")
    void boundsConcurrency() throws Exception {
        int permits = 3;
        HeavyToolLimiter limiter = limiter(permits, 10);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
        try {
            CountDownLatch done = new CountDownLatch(16);
            for (int i = 0; i < 16; i++) {
                pool.submit(() -> {
                    try {
                        limiter.call(() -> {
                            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                            Thread.sleep(20);
                            inFlight.decrementAndGet();
                            return null;
                        });
                    } catch (Throwable ignored) {
                        // shed requests are fine; we only care about the ceiling
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertTrue(peak.get() <= permits,
                    "concurrency ceiling breached: peak was " + peak.get() + " for " + permits + " permits");
        } finally {
            pool.shutdownNow();
        }
    }
}

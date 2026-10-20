package com.vishnu.pdf_studio_api.pdfstudioapi.credits;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.CreditProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.GrantKind;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.CreditsService;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.IpGrantGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The web tier mints guest accounts on demand, so per-account grant limits alone are meaningless —
 * these cover the per-IP rationing that actually bounds free credits there.
 */
@SpringBootTest
class IpGrantGuardTest {

    @Autowired IpGrantGuard guard;
    @Autowired CreditsService creditsService;
    @Autowired CreditProperties creditProperties;

    private String uniqueIp() {
        // Distinct per test so the daily counters never overlap between them.
        return "203.0.113." + (int) (Math.random() * 200) + "-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("allows grants up to the daily cap for an IP, then refuses")
    void capsGrantsPerIp() {
        String ip = uniqueIp();
        int cap = creditProperties.getWelcomeGrantsPerIpPerDay();
        assertTrue(cap > 0, "test assumes the cap is enabled");

        for (int i = 0; i < cap; i++) {
            assertTrue(guard.tryConsume(ip, GrantKind.WELCOME),
                    "grant " + (i + 1) + " of " + cap + " should be allowed");
        }
        assertFalse(guard.tryConsume(ip, GrantKind.WELCOME),
                "the grant past the cap must be refused");
    }

    @Test
    @DisplayName("counts each grant kind separately")
    void kindsAreIndependent() {
        String ip = uniqueIp();
        for (int i = 0; i < creditProperties.getWelcomeGrantsPerIpPerDay(); i++) {
            guard.tryConsume(ip, GrantKind.WELCOME);
        }
        assertFalse(guard.tryConsume(ip, GrantKind.WELCOME));
        assertTrue(guard.tryConsume(ip, GrantKind.DAILY),
                "exhausting welcome grants must not consume the daily allowance");
    }

    @Test
    @DisplayName("different IPs do not share a counter")
    void ipsAreIndependent() {
        String exhausted = uniqueIp();
        for (int i = 0; i < creditProperties.getWelcomeGrantsPerIpPerDay(); i++) {
            guard.tryConsume(exhausted, GrantKind.WELCOME);
        }
        assertFalse(guard.tryConsume(exhausted, GrantKind.WELCOME));
        assertTrue(guard.tryConsume(uniqueIp(), GrantKind.WELCOME));
    }

    @Test
    @DisplayName("a request with no resolvable IP still works, falling back to per-account limits")
    void missingIpDoesNotBlock() {
        assertTrue(guard.tryConsume(null, GrantKind.WELCOME));
        assertTrue(guard.tryConsume("", GrantKind.WELCOME));
    }

    @Test
    @DisplayName("once an IP is exhausted, new accounts from it open with a zero balance")
    void exhaustedIpGetsNoWelcomeCredits() {
        String ip = uniqueIp();
        // Burn the IP's welcome allowance on throwaway accounts.
        for (int i = 0; i < creditProperties.getWelcomeGrantsPerIpPerDay(); i++) {
            creditsService.getBalance("burn-" + UUID.randomUUID(), ip);
        }
        int balance = creditsService.getBalance(UUID.randomUUID().toString(), ip);
        assertEquals(0, balance,
                "clearing site data for a fresh guest must not yield another welcome balance");
    }

    // Removed: "accounts namespaced to different issuers are entirely separate".
    // The two deployments (app/web) collapsed into one issuer, so there is only one subject
    // space now — the invariant this test asserted no longer applies.
    // See AuthIssuerProperties javadoc and the Subjects/SubjectJwtAuthenticationConverter
    // rename that removed PrincipalKey.aliasOf.

    @Test
    @DisplayName("simultaneous first requests from one IP neither deadlock nor exceed the cap")
    void concurrentFirstRequestsAreSafe() throws Exception {
        // This is the case that broke in production. The counter used to SELECT ... FOR UPDATE a
        // row that did not exist yet, which takes a shared gap lock, so two requests from one IP
        // each held one and each then blocked on the other's insert: deadlock, surfacing as a 500
        // on the balance endpoint. Nothing covered concurrency here, so nothing caught it.
        final String ip = uniqueIp();
        final int cap = creditProperties.getWelcomeGrantsPerIpPerDay();
        final int racers = cap + 4;

        var pool = java.util.concurrent.Executors.newFixedThreadPool(racers);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var allowed = new java.util.concurrent.atomic.AtomicInteger();
            var failed = new java.util.concurrent.atomic.AtomicReference<Throwable>();

            var done = new java.util.concurrent.CountDownLatch(racers);
            for (int i = 0; i < racers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (guard.tryConsume(ip, GrantKind.WELCOME)) allowed.incrementAndGet();
                    } catch (Throwable t) {
                        failed.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "threads did not finish");

            assertNull(failed.get(), () -> "a racing request failed instead of being counted: " + failed.get());
            // The cap is the point: more racers than allowance, and the allowance still holds.
            assertEquals(cap, allowed.get(),
                    "exactly the cap should be allowed, no more and no fewer");
        } finally {
            pool.shutdownNow();
        }
    }
}

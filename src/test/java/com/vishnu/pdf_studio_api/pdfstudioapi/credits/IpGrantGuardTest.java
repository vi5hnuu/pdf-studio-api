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
            creditsService.getBalance("web:burn-" + UUID.randomUUID(), ip);
        }
        int balance = creditsService.getBalance("web:" + UUID.randomUUID(), ip);
        assertEquals(0, balance,
                "clearing site data for a fresh guest must not yield another welcome balance");
    }

    @Test
    @DisplayName("accounts namespaced to different issuers are entirely separate")
    void issuerNamespacesAreSeparate() {
        String subject = UUID.randomUUID().toString();
        String appUser = "app:" + subject;
        String webUser = "web:" + subject;   // same subject, different auth instance

        int appOpening = creditsService.getBalance(appUser, uniqueIp());
        creditsService.charge(appUser, "grayscale-pdf", 0L, "ns-" + subject, null);

        assertEquals(appOpening - 1, creditsService.getBalance(appUser, null));
        assertNotEquals(appOpening - 1, creditsService.getBalance(webUser, uniqueIp()),
                "the same subject from a different issuer must not share the balance");
    }
}

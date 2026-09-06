package com.vishnu.pdf_studio_api.pdfstudioapi.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The namespacing that keeps the two auth deployments' user spaces apart. Without it, the
 * same subject issued independently by both would resolve to one credit account.
 */
class NamespacedJwtAuthenticationConverterTest {

    private Jwt tokenFor(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("roles", List.of("ROLE_USER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    @Test
    @DisplayName("the principal name is prefixed with the issuer alias")
    void namespacesPrincipal() {
        var auth = new NamespacedJwtAuthenticationConverter("app").convert(tokenFor("abc-123"));
        assertNotNull(auth);
        assertEquals("app:abc-123", auth.getName());
    }

    @Test
    @DisplayName("the same subject from two issuers yields two distinct identities")
    void differentIssuersDoNotCollide() {
        String sharedSubject = "collision-candidate";
        var app = new NamespacedJwtAuthenticationConverter("app").convert(tokenFor(sharedSubject));
        var web = new NamespacedJwtAuthenticationConverter("web").convert(tokenFor(sharedSubject));

        assertNotNull(app);
        assertNotNull(web);
        assertNotEquals(app.getName(), web.getName(),
                "identical subjects from different auth instances must stay separate accounts");
    }

    @Test
    @DisplayName("roles are still mapped to authorities")
    void mapsRoles() {
        var auth = new NamespacedJwtAuthenticationConverter("app").convert(tokenFor("abc"));
        assertNotNull(auth);
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }

    @Test
    @DisplayName("PrincipalKey round-trips the two halves")
    void principalKeyRoundTrip() {
        String key = PrincipalKey.of("web", "abc-123");
        assertEquals("web:abc-123", key);
        assertEquals("web", PrincipalKey.aliasOf(key));
        assertEquals("abc-123", PrincipalKey.subjectOf(key));
    }
}

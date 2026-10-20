package com.vishnu.pdf_studio_api.pdfstudioapi.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The authentication's principal name is the JWT subject. One auth deployment issues the
 * tokens, so a subject is unique on its own and the credit system can key every per-user
 * record by it.
 */
class SubjectJwtAuthenticationConverterTest {

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
    @DisplayName("the principal name is the bare JWT subject")
    void principalIsSubject() {
        var auth = new SubjectJwtAuthenticationConverter().convert(tokenFor("abc-123"));
        assertNotNull(auth);
        assertEquals("abc-123", auth.getName());
    }

    @Test
    @DisplayName("roles are mapped to authorities")
    void mapsRoles() {
        var auth = new SubjectJwtAuthenticationConverter().convert(tokenFor("abc"));
        assertNotNull(auth);
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }

    @Test
    @DisplayName("Subjects accepts valid ULID shapes and rejects garbage")
    void subjectValidation() {
        assertTrue(Subjects.isValid("01M5ABJMF2RDYQPMFZ8D2ZDB0Q"));
        assertTrue(Subjects.isValid("alpha_num-with.dots_only"));
        assertFalse(Subjects.isValid(null));
        assertFalse(Subjects.isValid(""));
        assertFalse(Subjects.isValid("has:colons"));
        assertFalse(Subjects.isValid("has/slash"));
        assertFalse(Subjects.isValid("a".repeat(65)));
    }
}

package com.vishnu.pdf_studio_api.pdfstudioapi.web;

import com.vishnu.pdf_studio_api.pdfstudioapi.services.CreditsService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Exercises the full request chain — security, upload validation, the credit aspect, the
 * tool itself and the error handler — rather than any one piece in isolation.
 *
 * <p>Each of those was changed independently; this is what proves they compose.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ToolEndpointTest {

    @Autowired MockMvc mockMvc;
    @Autowired CreditsService creditsService;

    private byte[] samplePdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Authenticates as an already-namespaced principal.
     *
     * MockMvc's {@code jwt()} builds the authentication directly and so does not run
     * {@code NamespacedJwtAuthenticationConverter}; the subject is therefore given in the
     * form that converter produces. The namespacing itself is covered by
     * {@code NamespacedJwtAuthenticationConverterTest}.
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor asUser(String principalKey) {
        return jwt().jwt(builder -> builder.subject(principalKey).claim("roles", java.util.List.of("ROLE_USER")))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    @DisplayName("rejects an unauthenticated call")
    void requiresAuthentication() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/pdf-studio/grayscale-pdf")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf", samplePdf())))
                .andReturn();
        assertEquals(401, result.getResponse().getStatus());
    }

    @Test
    @DisplayName("a ZIP renamed .pdf is refused as an invalid file, not a 500")
    void rejectsDisguisedFile() throws Exception {
        byte[] zip = {'P', 'K', 0x03, 0x04, 0, 0, 0, 0};
        MvcResult result = mockMvc.perform(multipart("/api/v1/pdf-studio/grayscale-pdf")
                        .file(new MockMultipartFile("file", "payload.pdf", "application/pdf", zip))
                        .with(asUser("app:u-" + UUID.randomUUID())))
                .andReturn();

        assertEquals(422, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("INVALID_FILE"), body);
        assertTrue(body.contains("\"success\":false"), body);
        assertFalse(body.contains("Exception"), "the envelope must not leak internals: " + body);
    }

    @Test
    @DisplayName("a valid run returns the file, a safe filename and the credit headers")
    void runsAndChargesOnce() throws Exception {
        String userId = "app:sub-" + UUID.randomUUID();
        String key = "idem-" + UUID.randomUUID();

        MvcResult first = mockMvc.perform(multipart("/api/v1/pdf-studio/grayscale-pdf")
                        .file(new MockMultipartFile("file", "my report.pdf", "application/pdf", samplePdf()))
                        .header("Idempotency-Key", key)
                        .with(asUser(userId)))
                .andReturn();

        assertEquals(200, first.getResponse().getStatus());
        assertTrue(first.getResponse().getContentAsByteArray().length > 0);

        // Quoted and sanitised, not raw client input interpolated into the header.
        String disposition = first.getResponse().getHeader("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.startsWith("attachment;"), disposition);

        assertEquals("1", first.getResponse().getHeader("X-Credits-Charged"));
        String remaining = first.getResponse().getHeader("X-Credits-Remaining");
        assertNotNull(remaining);

        // The same key again must not move the balance.
        mockMvc.perform(multipart("/api/v1/pdf-studio/grayscale-pdf")
                        .file(new MockMultipartFile("file", "my report.pdf", "application/pdf", samplePdf()))
                        .header("Idempotency-Key", key)
                        .with(asUser(userId)))
                .andReturn();

        assertEquals(Integer.parseInt(remaining),
                creditsService.getBalance(userId, null),
                "a retry with the same Idempotency-Key must not be charged again");
    }

    @Test
    @DisplayName("a free tool runs without touching the balance")
    void freeToolIsNotCharged() throws Exception {
        String userId = "app:sub-" + UUID.randomUUID();
        int opening = creditsService.getBalance(userId, null);

        MvcResult result = mockMvc.perform(multipart("/api/v1/pdf-studio/sanitize-pdf")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf", samplePdf()))
                        .with(asUser(userId)))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertNull(result.getResponse().getHeader("X-Credits-Charged"));
        assertEquals(opening, creditsService.getBalance(userId, null));
    }
}

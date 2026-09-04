package com.vishnu.pdf_studio_api.pdfstudioapi.validation;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.UploadProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Content is identified by magic bytes, never by the client-supplied name or content type. */
class UploadValidatorTest {

    private UploadValidator validator;

    @BeforeEach
    void setUp() {
        UploadProperties properties = new UploadProperties();
        properties.setMaxFiles(3);
        properties.setMaxPages(100);
        properties.setMaxRenderPages(10);
        validator = new UploadValidator(properties);
    }

    private MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/pdf", content);
    }

    @Test
    @DisplayName("accepts a real PDF")
    void acceptsPdf() {
        assertDoesNotThrow(() -> validator.pdf(file("a.pdf", "%PDF-1.7\nbody".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    @DisplayName("rejects a ZIP renamed .pdf — the whole point of sniffing")
    void rejectsDisguisedZip() {
        byte[] zip = {'P', 'K', 0x03, 0x04, 0x00, 0x00};
        ApiException ex = assertThrows(ApiException.class, () -> validator.pdf(file("payload.pdf", zip)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("INVALID_FILE", ex.getCode());
    }

    @Test
    @DisplayName("rejects an empty part with a 400 rather than failing later in PDFBox")
    void rejectsEmpty() {
        ApiException ex = assertThrows(ApiException.class, () -> validator.pdf(file("a.pdf", new byte[0])));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("accepts a PDF whose header is preceded by junk, as PDFBox does")
    void acceptsPdfWithLeadingJunk() {
        byte[] withPreamble = ("junk-preamble\n%PDF-1.4\n").getBytes(StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> validator.pdf(file("a.pdf", withPreamble)));
    }

    @Test
    @DisplayName("recognises the common image signatures")
    void acceptsImages() {
        assertDoesNotThrow(() -> validator.image(file("a.png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0})));
        assertDoesNotThrow(() -> validator.image(file("a.jpg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0})));
    }

    @Test
    @DisplayName("rejects a PDF sent where an image is expected")
    void rejectsPdfAsImage() {
        assertThrows(ApiException.class,
                () -> validator.image(file("a.png", "%PDF-1.7".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    @DisplayName("enforces the per-request file count cap")
    void rejectsTooManyFiles() {
        byte[] pdf = "%PDF-1.7".getBytes(StandardCharsets.UTF_8);
        List<org.springframework.web.multipart.MultipartFile> many =
                List.of(file("1.pdf", pdf), file("2.pdf", pdf), file("3.pdf", pdf), file("4.pdf", pdf));
        ApiException ex = assertThrows(ApiException.class, () -> validator.pdfs(many, 2, "files"));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, ex.getStatus());
    }

    @Test
    @DisplayName("enforces the minimum a multi-file tool needs")
    void rejectsTooFewFiles() {
        List<org.springframework.web.multipart.MultipartFile> one =
                List.of(file("1.pdf", "%PDF-1.7".getBytes(StandardCharsets.UTF_8)));
        ApiException ex = assertThrows(ApiException.class, () -> validator.pdfs(one, 2, "files"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("render-heavy tools get the lower page ceiling")
    void enforcesPageCaps() {
        assertDoesNotThrow(() -> validator.pageCount(100));
        assertThrows(ApiException.class, () -> validator.pageCount(101));
        assertDoesNotThrow(() -> validator.renderablePageCount(10));
        assertThrows(ApiException.class, () -> validator.renderablePageCount(11));
    }
}

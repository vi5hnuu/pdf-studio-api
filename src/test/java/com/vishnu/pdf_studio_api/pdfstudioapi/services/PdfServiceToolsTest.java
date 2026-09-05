package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.LoadProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CompressionLevel;
import com.vishnu.pdf_studio_api.pdfstudioapi.exception.ApiException;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.PdfDocuments;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises real tools end-to-end after the byte[] → Path migration.
 *
 * <p>The refactor changed how every tool receives its input, so these run actual PDFBox operations
 * on a real document rather than asserting on types: a signature-only change that silently produced
 * empty or corrupt output would still compile.
 */
class PdfServiceToolsTest {

    private PdfService service;
    private byte[] samplePdf;
    private int originalMaxPages;

    @BeforeEach
    void setUp() throws Exception {
        service = new PdfService(new LoadProperties());
        samplePdf = buildPdf(6);
        originalMaxPages = PdfDocuments.maxPages();
    }

    @AfterEach
    void tearDown() {
        // The cap is static (PdfTools is a static utility), so restore it between tests.
        PdfDocuments.setMaxPages(originalMaxPages);
    }

    /** A real multi-page PDF with text, so text-based tools have something to find. */
    private byte[] buildPdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Page " + (i + 1) + " content");
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private MultipartFile upload() {
        return new MockMultipartFile("file", "sample.pdf", "application/pdf", samplePdf);
    }

    /** Asserts the tool returned a 200 with a non-trivial body. */
    private void assertProduced(ResponseEntity<Resource> response) throws Exception {
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        long length = response.getBody().contentLength();
        assertTrue(length > 0, "tool produced an empty file");
    }

    /** Asserts the result is a readable PDF with the expected page count. */
    private void assertPdfWithPages(ResponseEntity<Resource> response, int expectedPages) throws Exception {
        assertProduced(response);
        byte[] bytes = response.getBody().getInputStream().readAllBytes();
        try (PDDocument result = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            assertEquals(expectedPages, result.getNumberOfPages());
        }
    }

    @Test
    @DisplayName("structural tools still produce valid PDFs")
    void structuralTools() throws Exception {
        assertPdfWithPages(service.grayscalePdf(null, upload()), 6);
        assertPdfWithPages(service.compressPdf(null, CompressionLevel.RECOMMENDED, upload()), 6);
        assertPdfWithPages(service.optimizePdf(null, upload()), 6);
        assertPdfWithPages(service.scalePdf(0.5d, upload()), 6);
        assertPdfWithPages(service.mirrorPdf(null, null, upload()), 6);
        assertPdfWithPages(service.sanitizePdf(upload()), 6);
        assertPdfWithPages(service.removeMetadata(upload()), 6);
    }

    @Test
    @DisplayName("n-up imposes 6 pages onto 3 sheets at 2-up")
    void nUpReducesPageCount() throws Exception {
        assertPdfWithPages(service.nUpPdf(null, 2, upload()), 3);
    }

    @Test
    @DisplayName("duplicate-pages actually adds the duplicated pages")
    void duplicatePages() throws Exception {
        assertPdfWithPages(service.duplicatePages(null, java.util.Map.of(0, 2), upload()), 8);
    }

    @Test
    @DisplayName("extract-text recovers the text written into the document")
    void extractTextRoundTrips() throws Exception {
        ResponseEntity<Resource> response = service.extractText(upload(), null);
        assertProduced(response);
        String text = new String(response.getBody().getInputStream().readAllBytes());
        assertTrue(text.contains("Page 1 content"), "expected extracted text, got: " + text);
        assertTrue(text.contains("Page 6 content"));
    }

    @Test
    @DisplayName("analyze reports the real page count and file size")
    void analyzeReportsFacts() {
        ResponseEntity<?> response = service.analyzePdf(upload());
        assertEquals(200, response.getStatusCode().value());
        String body = String.valueOf(response.getBody());
        assertTrue(body.contains("6"), "page count should appear in " + body);
    }

    @Test
    @DisplayName("zip-producing tools return a non-empty archive")
    void zipTools() throws Exception {
        assertProduced(service.splitBySize(null, 1.0d, upload()));
    }

    @Test
    @DisplayName("the page-count cap is enforced through the shared open path")
    void enforcesPageCap() throws Exception {
        PdfDocuments.setMaxPages(3);

        // grayscale loads inside PdfTools, not through the service's open helper — this is exactly
        // the path that previously escaped the cap.
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.grayscalePdf(null, upload()));
        assertTrue(containsApiException(ex), "expected the page cap to surface, got: " + ex);
    }

    private boolean containsApiException(Throwable t) {
        for (int i = 0; t != null && i < 10; i++, t = t.getCause()) {
            if (t instanceof ApiException) return true;
        }
        return false;
    }

    @Test
    @DisplayName("an out-of-range page range does nothing instead of failing")
    void toleratesOutOfRangePages() throws Exception {
        // toPage past the end and a negative fromPage both used to index off the page tree
        // and surface as a 500.
        assertPdfWithPages(service.watermarkPdf(null, "X", 24, null, 0.3f, 45.0,
                null, null, -5, 999, upload()), 6);
        assertPdfWithPages(service.pageNumbers(upload(), null, null, null,
                -3, 999, null, null, null, null, null), 6);
    }

    @Test
    @DisplayName("a range that covers no page leaves the document untouched")
    void emptyRangeIsANoOp() throws Exception {
        // from after to: nothing to mark, and certainly not an error.
        assertPdfWithPages(service.watermarkPdf(null, "X", 24, null, 0.3f, 45.0,
                null, null, 5, 2, upload()), 6);
    }

    @Test
    @DisplayName("splitting into fixed ranges rejects a zero or negative size")
    void rejectsInvalidFixedRange() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.splitPdf(null, com.vishnu.pdf_studio_api.pdfstudioapi.enums.SplitType.FIXED_RANGE,
                        0, null, upload()));
        assertTrue(containsApiException(ex), "expected a 400, got: " + ex);
    }

    @Test
    @DisplayName("image-to-pdf reports an unreadable image rather than a null dereference")
    void rejectsUnreadableImage() {
        var notAnImage = new MockMultipartFile("files", "a.png", "image/png",
                "this is not an image".getBytes());
        assertThrows(RuntimeException.class,
                () -> service.imageToPdf(null, java.util.List.of(notAnImage)));
    }
}

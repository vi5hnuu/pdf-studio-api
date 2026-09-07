package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.LoadProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.UploadProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PageSizePreset;
import com.vishnu.pdf_studio_api.pdfstudioapi.validation.UploadValidator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the tools that used to rewrite every page whether or not you wanted them to.
 *
 * <p>Crop, greyscale, scale and resize-page had no way to say "just these pages", so fixing three
 * bad scans meant changing the whole document. The assertions that matter here are the negative
 * ones: an unselected page must come out untouched, which is the part a careless range
 * implementation gets wrong.
 */
class PageRangeTest {

    private PdfService service;
    private byte[] samplePdf;

    @BeforeEach
    void setUp() throws Exception {
        service = new PdfService(new LoadProperties(), new UploadValidator(new UploadProperties()));
        samplePdf = buildPdf(4);
    }

    @Test
    @DisplayName("crop trims only the selected pages and leaves the rest at full size")
    void cropAppliesToSelectedPagesOnly() throws Exception {
        byte[] result = bytesOf(service.cropPdf(null, 20f, 20f, 20f, 20f, List.of(1, 2), upload()));

        try (PDDocument doc = Loader.loadPDF(result)) {
            assertEquals(612f, doc.getPage(0).getCropBox().getWidth(), 0.5f, "page 1 was not selected");
            assertEquals(572f, doc.getPage(1).getCropBox().getWidth(), 0.5f);
            assertEquals(572f, doc.getPage(2).getCropBox().getWidth(), 0.5f);
            assertEquals(612f, doc.getPage(3).getCropBox().getWidth(), 0.5f, "page 4 was not selected");
        }
    }

    @Test
    @DisplayName("an empty selection still crops the whole document, as it always did")
    void emptySelectionMeansEveryPage() throws Exception {
        byte[] result = bytesOf(service.cropPdf(null, 20f, 20f, 20f, 20f, List.of(), upload()));

        try (PDDocument doc = Loader.loadPDF(result)) {
            for (PDPage page : doc.getPages()) {
                assertEquals(572f, page.getCropBox().getWidth(), 0.5f);
            }
        }
    }

    @Test
    @DisplayName("scale resizes only the selected pages")
    void scaleAppliesToSelectedPagesOnly() throws Exception {
        byte[] result = bytesOf(service.scalePdf(0.5d, List.of(2), upload()));

        try (PDDocument doc = Loader.loadPDF(result)) {
            assertEquals(612f, doc.getPage(0).getMediaBox().getWidth(), 0.5f);
            assertEquals(612f, doc.getPage(1).getMediaBox().getWidth(), 0.5f);
            assertEquals(306f, doc.getPage(2).getMediaBox().getWidth(), 0.5f);
            assertEquals(612f, doc.getPage(3).getMediaBox().getWidth(), 0.5f);
        }
    }

    @Test
    @DisplayName("resize-page re-lays only the selected pages onto the new size")
    void resizeAppliesToSelectedPagesOnly() throws Exception {
        byte[] result = bytesOf(service.resizePage(PageSizePreset.A4, List.of(0), upload()));

        try (PDDocument doc = Loader.loadPDF(result)) {
            assertEquals(595f, doc.getPage(0).getMediaBox().getWidth(), 0.5f);
            assertEquals(612f, doc.getPage(1).getMediaBox().getWidth(), 0.5f,
                    "an unselected page keeps its original size");
        }
    }

    @Test
    @DisplayName("greyscale rasterises the selected pages and keeps the others as real text")
    void grayscaleLeavesUnselectedPagesAsVector() throws Exception {
        byte[] result = bytesOf(service.grayscalePdf(null, List.of(0), upload()));

        try (PDDocument doc = Loader.loadPDF(result)) {
            assertEquals(4, doc.getNumberOfPages());

            // The whole point of not re-rendering an unselected page: its text is still text.
            // A rasterised page has none, so this distinguishes the two paths precisely.
            assertEquals("", textOf(doc, 1).trim(), "page 1 was converted and should be an image");
            assertTrue(textOf(doc, 2).contains("Page 2"),
                    "an unselected page should keep its selectable text");
            assertTrue(textOf(doc, 4).contains("Page 4"));
        }
    }

    @Test
    @DisplayName("greyscaling everything still leaves no extractable text")
    void grayscaleWithNoSelectionConvertsEverything() throws Exception {
        byte[] result = bytesOf(service.grayscalePdf(null, null, upload()));

        try (PDDocument doc = Loader.loadPDF(result)) {
            for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                assertEquals("", textOf(doc, page).trim());
            }
        }
    }

    @Test
    @DisplayName("a selection naming pages that do not exist changes nothing rather than failing")
    void outOfRangeSelectionIsHarmless() throws Exception {
        byte[] result = bytesOf(service.scalePdf(0.5d, List.of(50, 60), upload()));

        try (PDDocument doc = Loader.loadPDF(result)) {
            for (PDPage page : doc.getPages()) {
                assertEquals(612f, page.getMediaBox().getWidth(), 0.5f);
            }
        }
    }

    @Test
    @DisplayName("pdf-to-jpg renders only the requested pages, not all 4")
    void pdfToJpgRendersSelectedPagesOnly() throws Exception {
        byte[] zip = bytesOf(service.pdfToJpg(upload(), null, null, false, null, null, List.of(1, 3)));

        List<String> entries = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zip))) {
            for (java.util.zip.ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                entries.add(entry.getName());
            }
        }
        assertEquals(List.of("page_2.jpg", "page_4.jpg"), entries,
                "entries keep their original page numbers so the user can tell which is which");
    }

    @Test
    @DisplayName("extract-text reads only the requested pages, including non-contiguous ones")
    void extractTextReadsSelectedPagesOnly() throws Exception {
        String text = new String(bytesOf(service.extractText(upload(), null, List.of(0, 2))));

        assertTrue(text.contains("Page 1"));
        assertFalse(text.contains("Page 2"), "page 2 was not selected");
        assertTrue(text.contains("Page 3"));
        assertFalse(text.contains("Page 4"), "page 4 was not selected");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────

    private MultipartFile upload() {
        return new MockMultipartFile("file", "sample.pdf", "application/pdf", samplePdf);
    }

    private static String textOf(PDDocument doc, int oneBasedPage) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(oneBasedPage);
        stripper.setEndPage(oneBasedPage);
        return stripper.getText(doc);
    }

    private static byte[] bytesOf(ResponseEntity<Resource> response) throws IOException {
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        return response.getBody().getInputStream().readAllBytes();
    }

    private static byte[] buildPdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER); // 612 x 792
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
}

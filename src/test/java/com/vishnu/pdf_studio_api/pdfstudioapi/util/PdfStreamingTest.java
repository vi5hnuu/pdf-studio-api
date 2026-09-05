package com.vishnu.pdf_studio_api.pdfstudioapi.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Proves the temp-file path round-trips a real PDF and never leaves files behind. */
class PdfStreamingTest {

    /** A genuine PDFBox-produced PDF, so we are testing the real parser, not a stub. */
    private byte[] samplePdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("an upload becomes a temp file with identical bytes, deleted on close")
    void materialisesAndCleansUp() throws Exception {
        byte[] pdf = samplePdf(3);
        MockMultipartFile upload = new MockMultipartFile("file", "a.pdf", "application/pdf", pdf);

        Path captured;
        try (TempFiles.Handle handle = TempFiles.of(upload, ".pdf")) {
            captured = handle.path();
            assertTrue(Files.exists(captured), "temp file should exist while open");
            assertArrayEquals(pdf, Files.readAllBytes(captured), "bytes must survive the transfer");
            assertEquals(pdf.length, handle.size());
        }
        assertFalse(Files.exists(captured), "temp file must be deleted on close");
    }

    @Test
    @DisplayName("PDFBox reads the document from the temp file under both cache strategies")
    void loadsUnderBothCacheStrategies() throws Exception {
        byte[] pdf = samplePdf(5);
        MockMultipartFile upload = new MockMultipartFile("file", "a.pdf", "application/pdf", pdf);

        try (TempFiles.Handle handle = TempFiles.of(upload, ".pdf")) {
            // Threshold above the file size -> memory cache (the fast path for small files).
            try (PDDocument memoryBacked = PdfDocuments.load(handle.path(), Long.MAX_VALUE)) {
                assertEquals(5, memoryBacked.getNumberOfPages());
            }
            // Threshold of 0 -> temp-file cache (the path large documents take).
            try (PDDocument fileBacked = PdfDocuments.load(handle.path(), 0)) {
                assertEquals(5, fileBacked.getNumberOfPages());
            }
        }
    }

    @Test
    @DisplayName("OpenPdf closes the document and removes the backing file together")
    void openPdfClosesBoth() throws Exception {
        byte[] pdf = samplePdf(2);
        MockMultipartFile upload = new MockMultipartFile("file", "a.pdf", "application/pdf", pdf);

        TempFiles.Handle handle = TempFiles.of(upload, ".pdf");
        Path path = handle.path();
        OpenPdf opened = new OpenPdf(handle, PdfDocuments.load(path, 0));
        assertEquals(2, opened.document().getNumberOfPages());

        opened.close();
        assertFalse(Files.exists(path), "backing temp file must be gone after close");
    }

    @Test
    @DisplayName("a corrupt upload fails without leaking its temp file")
    void cleansUpWhenParsingFails() throws Exception {
        MockMultipartFile bad = new MockMultipartFile("file", "a.pdf", "application/pdf",
                "%PDF-1.7 but truncated nonsense".getBytes());

        Path path;
        try (TempFiles.Handle handle = TempFiles.of(bad, ".pdf")) {
            path = handle.path();
            assertThrows(Exception.class, () -> PdfDocuments.load(path, 0));
        }
        assertFalse(Files.exists(path), "temp file must not survive a failed parse");
    }
}

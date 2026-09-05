package com.vishnu.pdf_studio_api.pdfstudioapi.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DownloadResponseTest {

    @Test
    @DisplayName("quotes the filename and neutralises injected header syntax")
    void buildsSafeHeader() {
        String header = DownloadResponse.header("evil\";\r\nX-Injected: yes", "document", "pdf");

        assertTrue(header.startsWith("attachment;"), header);
        assertFalse(header.contains("X-Injected: yes"), "injected header must not survive: " + header);
        assertFalse(header.contains("\r") || header.contains("\n"), "header must be single-line");
    }

    @Test
    @DisplayName("emits a plain quoted filename, not a mail encoded-word")
    void doesNotUseMailEncoding() {
        String header = DownloadResponse.header("My Report", "document", "pdf");

        // Spring's charset overload wraps the value as =?UTF-8?Q?...?= (RFC 2047), which is a
        // mail encoding; clients reading only `filename=` saved that literal string.
        assertFalse(header.contains("=?"), "must not be a mail encoded-word: " + header);
        assertTrue(header.contains("filename=\"My Report.pdf\""), header);
    }

    @Test
    @DisplayName("appends the extension exactly once, even when the name carries one")
    void appendsExtensionOnce() {
        assertTrue(DownloadResponse.header("report.pdf", "document", "pdf").contains("report.pdf"));
        assertFalse(DownloadResponse.header("report.pdf", "document", "pdf").contains("report.pdf.pdf"));
    }
}

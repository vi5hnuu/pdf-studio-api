package com.vishnu.pdf_studio_api.pdfstudioapi.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The client controls {@code outFileName}; these lock in that it can never reach a header raw. */
class FileNamesTest {

    @Test
    @DisplayName("strips characters that could break or split a Content-Disposition header")
    void sanitisesHeaderBreakingCharacters() {
        // A quote closes the filename token early; CR/LF splits the header entirely.
        String hostile = "report\";\r\nX-Injected: evil";
        String safe = FileNames.sanitize(hostile);

        assertFalse(safe.contains("\""), "quote must not survive");
        assertFalse(safe.contains("\r") || safe.contains("\n"), "newlines must not survive");
        assertFalse(safe.contains(";"), "semicolon must not survive");
    }

    @Test
    @DisplayName("drops directory components so a name can never traverse")
    void stripsPathTraversal() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"));
        assertEquals("file", FileNames.sanitize("C:\\Windows\\file.txt"));
        // A name that is only traversal tokens leaves nothing, so the fallback is used.
        assertEquals("output", FileNames.safeBaseName("../..", null));
    }

    @Test
    @DisplayName("keeps ordinary names readable rather than over-sanitising")
    void preservesFriendlyNames() {
        assertEquals("Q3 Report (final)-v2", FileNames.sanitize("Q3 Report (final)-v2.pdf"));
    }

    @Test
    @DisplayName("falls back only when the provided name yields nothing usable")
    void usesFallbackWhenEmpty() {
        assertEquals("merged", FileNames.safeBaseName(null, "merged"));
        assertEquals("merged", FileNames.safeBaseName("   ", "merged"));
        assertEquals("real", FileNames.safeBaseName("real.pdf", "merged"));
    }

    @Test
    @DisplayName("truncates absurdly long names to a filesystem-safe length")
    void truncatesLongNames() {
        assertTrue(FileNames.sanitize("a".repeat(500)).length() <= 120);
    }
}

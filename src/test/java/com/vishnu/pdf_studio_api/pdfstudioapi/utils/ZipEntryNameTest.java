package com.vishnu.pdf_studio_api.pdfstudioapi.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the names extract-embedded-files puts in its archive.
 *
 * <p>An attachment's name is whatever the uploaded PDF says it is, so it is attacker-controlled:
 * used verbatim, {@code ../../../.ssh/authorized_keys} produced an archive that writes outside
 * the extraction directory on any client that extracts naively.
 *
 * <p>Reducing the name then creates a second problem, which is the other half of this test: two
 * distinct attachments can reduce to one name, and a repeated entry name makes
 * {@code ZipOutputStream.putNextEntry} throw and fail the whole extraction.
 */
class ZipEntryNameTest {

    private String safeName(String raw, int ordinal, Set<String> taken) throws Exception {
        Method m = PdfTools.class.getDeclaredMethod(
                "safeZipEntryName", String.class, int.class, Set.class);
        m.setAccessible(true);
        return (String) m.invoke(null, raw, ordinal, taken);
    }

    @Test
    @DisplayName("a traversing name is reduced to a bare filename")
    void stripsTraversal() throws Exception {
        final Set<String> taken = new HashSet<>();
        for (String raw : new String[]{
                "../../../../home/user/.ssh/authorized_keys",
                "..\\..\\windows\\system32\\drivers\\etc\\hosts",
                "/etc/passwd",
                "....//....//x.txt"}) {
            String safe = safeName(raw, 1, taken);
            assertFalse(safe.contains("/"), raw + " -> " + safe);
            assertFalse(safe.contains("\\"), raw + " -> " + safe);
            assertFalse(safe.startsWith("."), raw + " -> " + safe);
            assertNotEquals("..", safe);
        }
    }

    @Test
    @DisplayName("an ordinary name is kept as it is")
    void keepsOrdinaryNames() throws Exception {
        assertEquals("report.pdf", safeName("report.pdf", 1, new HashSet<>()));
        assertEquals("my-notes_2.txt", safeName("my-notes_2.txt", 1, new HashSet<>()));
    }

    @Test
    @DisplayName("a name left empty by the reduction still gets one")
    void namesTheUnnameable() throws Exception {
        assertEquals("attachment_7", safeName("...", 7, new HashSet<>()));
        assertEquals("attachment_3", safeName(null, 3, new HashSet<>()));
    }

    @Test
    @DisplayName("two attachments that reduce to one name both get an entry")
    void makesCollisionsUnique() throws Exception {
        final Set<String> taken = new HashSet<>();
        // Distinct files whose paths differ only in a directory that the reduction removes.
        assertEquals("x.txt", safeName("notes/x.txt", 1, taken));
        assertEquals("x (2).txt", safeName("scans/x.txt", 2, taken));
        assertEquals("x (3).txt", safeName("other/x.txt", 3, taken));
        // Extensionless names uniquify too.
        assertEquals("README", safeName("README", 4, taken));
        assertEquals("README (2)", safeName("docs/README", 5, taken));
        assertEquals(5, taken.size(), "every returned name must be distinct");
    }

    @Test
    @DisplayName("a name made only of characters the reduction strips cannot collide either")
    void unnameableCollisionsAlsoUnique() throws Exception {
        final Set<String> taken = new HashSet<>();
        final String first = safeName("???", 1, taken);
        final String second = safeName("***", 2, taken);
        assertNotEquals(first, second);
        assertTrue(taken.contains(first) && taken.contains(second));
    }
}

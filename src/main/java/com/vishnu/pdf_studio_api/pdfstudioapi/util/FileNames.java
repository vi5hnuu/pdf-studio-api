package com.vishnu.pdf_studio_api.pdfstudioapi.util;

/**
 * Normalises user-supplied output file names before they reach a response header or the filesystem.
 *
 * <p>{@code outFileName} arrives from the client on nearly every endpoint and was previously
 * interpolated straight into {@code Content-Disposition}. A name containing a quote, semicolon or
 * newline could truncate or split the header; one containing {@code ../} or a path separator is a
 * traversal risk the moment a tool writes to disk (which the streaming work does). This collapses
 * anything unsafe to a single safe token.
 */
public final class FileNames {

    /** Longest name we emit — comfortably under the 255-byte limit common to most filesystems. */
    private static final int MAX_LENGTH = 120;

    /** Anything outside this set is replaced with '_'. Deliberately conservative. */
    private static final String UNSAFE = "[^A-Za-z0-9 ._()\\-\\[\\]]";

    private FileNames() {}

    /**
     * Returns a safe base name (no extension, no path), falling back when the input is unusable.
     *
     * @param provided the client-supplied name; may be null/blank
     * @param fallback used when {@code provided} is absent or sanitises to nothing
     */
    public static String safeBaseName(String provided, String fallback) {
        String candidate = sanitize(provided);
        if (!candidate.isEmpty()) return candidate;
        String safeFallback = sanitize(fallback);
        return safeFallback.isEmpty() ? "output" : safeFallback;
    }

    /**
     * Strips the directory portion and extension, removes unsafe characters, and truncates.
     * Returns an empty string when nothing usable remains.
     */
    public static String sanitize(String name) {
        if (name == null || name.isBlank()) return "";

        // Drop any directory component (handles both separators, and Windows-style input).
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);

        base = stripExtension(base);
        base = base.replaceAll(UNSAFE, "_").trim();

        // Collapse runs of separators introduced by the replacement, and trim leading dots so the
        // result can never be a hidden file or a "." / ".." traversal token.
        base = base.replaceAll("_{2,}", "_").replaceAll("^[._]+", "").trim();

        if (base.length() > MAX_LENGTH) base = base.substring(0, MAX_LENGTH).trim();
        return base;
    }

    /** Removes a trailing extension, if present. {@code report.pdf} → {@code report}. */
    public static String stripExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}

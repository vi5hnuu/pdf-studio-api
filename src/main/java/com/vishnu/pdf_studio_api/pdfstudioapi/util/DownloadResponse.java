package com.vishnu.pdf_studio_api.pdfstudioapi.util;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/**
 * Builds every tool's file download response.
 *
 * <p>Centralised for two reasons. First, correctness: the header is produced by
 * {@link ContentDisposition}, which quotes and RFC 5987-encodes the filename, replacing 36
 * hand-written {@code String.format("attachment; filename=%s.pdf", …)} call sites that interpolated
 * user input unescaped. Second, so the switch to streamed temp files needs one change here rather
 * than one per tool.
 *
 * @see FileNames for the name sanitising applied before it reaches the header
 */
public final class DownloadResponse {

    public static final MediaType PDF = MediaType.APPLICATION_PDF;
    public static final MediaType ZIP = MediaType.parseMediaType("application/zip");
    public static final MediaType OCTET = MediaType.APPLICATION_OCTET_STREAM;

    private DownloadResponse() {}

    /**
     * @param bytes     the file body
     * @param baseName  client-supplied name; sanitised, extension ignored
     * @param fallback  name used when {@code baseName} is absent or unusable
     * @param extension without the dot, e.g. {@code "pdf"}
     */
    public static ResponseEntity<Resource> of(byte[] bytes, String baseName, String fallback,
                                              String extension, MediaType contentType) {
        String filename = FileNames.safeBaseName(baseName, fallback) + "." + extension;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(bytes.length);
        headers.setContentType(contentType);
        // Results are per-request and often sensitive; never let a proxy or browser retain them.
        headers.setCacheControl("no-store");

        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(bytes));
    }

    /** Convenience for the common PDF case. */
    public static ResponseEntity<Resource> pdf(byte[] bytes, String baseName, String fallback) {
        return of(bytes, baseName, fallback, "pdf", PDF);
    }

    /**
     * Builds just the {@code Content-Disposition} header value, for the many call sites that
     * assemble their own {@link HttpHeaders}.
     *
     * <p>The name is sanitised by {@link FileNames} and then quoted and RFC 5987-encoded by
     * {@link ContentDisposition}, so a name containing a quote, semicolon or newline can no longer
     * truncate or split the header.
     *
     * @param baseName  client-supplied name; sanitised, any extension dropped
     * @param fallback  used when {@code baseName} is absent or sanitises to nothing
     * @param extension without the dot, e.g. {@code "pdf"}
     */
    public static String header(String baseName, String fallback, String extension) {
        return ContentDisposition.attachment()
                .filename(FileNames.safeBaseName(baseName, fallback) + "." + extension,
                        StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    /** Convenience for tools that return a ZIP archive. */
    public static ResponseEntity<Resource> zip(byte[] bytes, String baseName, String fallback) {
        return of(bytes, baseName, fallback, "zip", ZIP);
    }
}

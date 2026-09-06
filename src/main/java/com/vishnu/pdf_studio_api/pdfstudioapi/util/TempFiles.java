package com.vishnu.pdf_studio_api.pdfstudioapi.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Spills uploads and results to disk instead of holding them in the heap.
 *
 * <p>{@code MultipartFile.getBytes()} materialises the entire upload as a {@code byte[]}. Combined
 * with PDFBox's in-memory document and a {@code ByteArrayOutputStream} for the result, peak heap
 * ran to roughly three to four times the file size <em>per concurrent request</em>. Spring has
 * already written the upload to a temp file by the time it reaches a controller, so moving to a
 * {@link Path} removes a copy rather than adding I/O.
 *
 * <p>Every temp file created here is deleted by the caller's {@code try}-with-resources via
 * {@link Handle}; nothing relies on {@code deleteOnExit}, which leaks for a long-lived process.
 */
@Slf4j
public final class TempFiles {

    private static final String PREFIX = "pdfstudio-";

    private TempFiles() {}

    /**
     * Materialises an upload as a temp file.
     *
     * <p>Uses {@link MultipartFile#transferTo(Path)} where possible, which for a disk-backed part
     * is a filesystem move — no bytes pass through the heap at all.
     */
    public static Handle of(MultipartFile file, String suffix) throws IOException {
        Path path = Files.createTempFile(PREFIX, suffix);
        try {
            // transferTo may move the underlying part; if it has already been read it falls back to
            // a stream copy, so handle both.
            try {
                file.transferTo(path.toFile());
            } catch (IllegalStateException alreadyMoved) {
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return new Handle(path);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(path);
            throw e;
        }
    }

    /** Creates an empty temp file for a tool to write its result into. */
    public static Handle create(String suffix) throws IOException {
        return new Handle(Files.createTempFile(PREFIX, suffix));
    }

    static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Worth knowing about (the temp dir will grow) but never worth failing a request over.
            log.warn("Could not delete temp file {}: {}", path, e.getMessage());
        }
    }

    /**
     * A temp file whose lifetime is the enclosing {@code try}-with-resources block.
     *
     * <p>Deliberately not {@code deleteOnExit}: this process runs for weeks, and that list only
     * grows.
     */
    public record Handle(Path path) implements AutoCloseable {

        public java.io.File file() {
            return path.toFile();
        }

        public long size() throws IOException {
            return Files.size(path);
        }

        @Override
        public void close() {
            deleteQuietly(path);
        }
    }
}

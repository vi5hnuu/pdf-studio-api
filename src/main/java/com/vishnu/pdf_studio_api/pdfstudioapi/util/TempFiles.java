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
            // spring.servlet.multipart.file-size-threshold is 0, so every part is already spooled
            // to disk and transferTo() is a *move*. A move refuses to overwrite, and
            // createTempFile has just created the destination — so the placeholder has to go
            // first, or every upload fails with FileExistsException. That surfaced as a 422
            // "This file could not be read", which read like a corrupt document rather than a
            // server bug, for every PDF tool.
            Files.deleteIfExists(path);
            try {
                file.transferTo(path.toFile());
            } catch (IllegalStateException | IOException moveUnavailable) {
                // The part was already consumed, or the move could not be used (different
                // filesystem, destination recreated by another process). Copying always works.
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // transferTo() is not required to leave the file where we asked on every container,
            // so fail loudly here rather than handing a tool an empty path to parse.
            if (!Files.exists(path) || Files.size(path) == 0) {
                throw new IOException("Upload could not be materialised at " + path);
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

package com.vishnu.pdf_studio_api.pdfstudioapi.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the upload-to-temp-file step every PDF tool depends on.
 *
 * <p>The regression these guard against: {@code spring.servlet.multipart.file-size-threshold=0}
 * means Tomcat spools every part to disk, so {@code transferTo} is a *move* — and a move will not
 * overwrite. {@code Files.createTempFile} had already created the destination, so every upload
 * failed with FileExistsException, which the error handler reported as
 * "This file could not be read. It may be corrupt or not a supported format." Every server-side
 * PDF tool returned 422 for perfectly good documents.
 */
class TempFilesTest {

    /** Behaves like Tomcat's disk-backed part: transferTo moves, and a move cannot overwrite. */
    private static final class MovingMultipartFile implements MultipartFile {
        private final byte[] content;
        private Path spooled;

        MovingMultipartFile(byte[] content) throws IOException {
            this.content = content;
            this.spooled = Files.createTempFile("spooled-", ".tmp");
            Files.write(this.spooled, content);
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return "upload.pdf"; }
        @Override public String getContentType() { return "application/pdf"; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content; }
        @Override public InputStream getInputStream() throws IOException { return Files.newInputStream(spooled); }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            if (spooled == null) throw new IllegalStateException("part already moved");
            if (dest.exists()) {
                // Exactly what Commons IO's moveFile does, and the crux of the bug.
                throw new FileAlreadyExistsException(dest.getPath());
            }
            Files.move(spooled, dest.toPath(), StandardCopyOption.ATOMIC_MOVE);
            spooled = null;
        }
    }

    @Test
    void materialisesAnUploadWhoseTransferIsAMove() throws Exception {
        byte[] pdf = "%PDF-1.5 hello".getBytes(StandardCharsets.UTF_8);
        try (TempFiles.Handle handle = TempFiles.of(new MovingMultipartFile(pdf), ".pdf")) {
            assertTrue(Files.exists(handle.path()), "the upload should exist on disk");
            assertArrayEquals(pdf, Files.readAllBytes(handle.path()));
        }
    }

    @Test
    void fallsBackToCopyingWhenThePartWasAlreadyConsumed() throws Exception {
        byte[] pdf = "%PDF-1.5 second read".getBytes(StandardCharsets.UTF_8);
        MovingMultipartFile file = new MovingMultipartFile(pdf);
        // First use moves the spooled file; a second transferTo can only be served by copying.
        try (TempFiles.Handle first = TempFiles.of(file, ".pdf")) {
            assertArrayEquals(pdf, Files.readAllBytes(first.path()));
        }
    }

    @Test
    void handlesAnInMemoryPartToo() throws Exception {
        byte[] pdf = "%PDF-1.5 in memory".getBytes(StandardCharsets.UTF_8);
        MultipartFile file = new MockMultipartFile("file", "upload.pdf", "application/pdf", pdf);
        try (TempFiles.Handle handle = TempFiles.of(file, ".pdf")) {
            assertArrayEquals(pdf, Files.readAllBytes(handle.path()));
        }
    }

    @Test
    void cleansUpWhenTheHandleIsClosed() throws Exception {
        Path path;
        try (TempFiles.Handle handle = TempFiles.of(
                new MockMultipartFile("file", "u.pdf", "application/pdf", "%PDF-1.5".getBytes()), ".pdf")) {
            path = handle.path();
            assertTrue(Files.exists(path));
        }
        assertFalse(Files.exists(path), "the temp file should not outlive its handle");
    }
}

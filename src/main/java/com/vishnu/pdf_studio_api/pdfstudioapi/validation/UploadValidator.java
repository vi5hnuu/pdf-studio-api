package com.vishnu.pdf_studio_api.pdfstudioapi.validation;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.UploadProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.ArtworkKind;
import com.vishnu.pdf_studio_api.pdfstudioapi.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Gate every upload passes before any tool touches it.
 *
 * <p>Previously nothing checked what arrived: a ZIP renamed {@code .pdf}, an empty part, or a
 * thousand-file merge all went straight into PDFBox, which then failed deep in parsing and surfaced
 * as an opaque 500. Content is identified by <b>magic bytes</b> rather than the client-supplied
 * filename or {@code Content-Type}, both of which are trivially wrong or forged.
 *
 * <p>Page-count limits live here too but are applied by the caller once a document is open
 * ({@link #pageCount}/{@link #renderablePageCount}), since page count cannot be known without parsing.
 */
@Component
@RequiredArgsConstructor
public class UploadValidator {

    /** Enough to cover every signature below plus the slack PDFs are allowed before {@code %PDF-}. */
    private static final int SNIFF_BYTES = 1024;

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87 = {'G', 'I', 'F', '8', '7', 'a'};
    private static final byte[] GIF89 = {'G', 'I', 'F', '8', '9', 'a'};
    private static final byte[] BMP = {'B', 'M'};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};   // WebP container
    private static final byte[] WEBP = {'W', 'E', 'B', 'P'};   // at offset 8
    private static final byte[] TIFF_LE = {'I', 'I', 0x2A, 0x00};
    private static final byte[] TIFF_BE = {'M', 'M', 0x00, 0x2A};

    private final UploadProperties properties;

    // ── PDF ───────────────────────────────────────────────────────────────────────

    /** Validates a single required PDF part. */
    public void pdf(MultipartFile file, String partName) {
        requirePresent(file, partName);
        byte[] head = sniff(file);
        // The spec allows junk before the header, and real-world PDFs exploit that, so scan the
        // first block rather than requiring offset 0 — this matches what PDFBox itself tolerates.
        if (indexOf(head, PDF) < 0) {
            throw ApiException.invalidFile(
                    "'" + describe(file) + "' is not a PDF file. Upload a .pdf and try again.");
        }
    }

    public void pdf(MultipartFile file) {
        pdf(file, "file");
    }

    /** Validates a multi-PDF part, enforcing the minimum the tool needs and the global file cap. */
    public void pdfs(List<MultipartFile> files, int minimum, String partName) {
        requireCount(files, minimum, partName);
        for (MultipartFile file : files) pdf(file, partName);
    }

    // ── Images ────────────────────────────────────────────────────────────────────

    /** Validates a single required image part (JPEG, PNG, GIF, BMP, WebP or TIFF). */
    public void image(MultipartFile file, String partName) {
        requirePresent(file, partName);
        if (!looksLikeImage(sniff(file))) {
            throw ApiException.invalidFile(
                    "'" + describe(file) + "' is not a supported image. Use JPG, PNG, GIF, BMP or WebP.");
        }
    }

    public void image(MultipartFile file) {
        image(file, "file");
    }

    public void images(List<MultipartFile> files, int minimum, String partName) {
        requireCount(files, minimum, partName);
        for (MultipartFile file : files) image(file, partName);
    }

    // ── Either ────────────────────────────────────────────────────────────────────

    /**
     * Validates a part that may be either a PDF or an image, and reports which it is.
     *
     * <p>Stamping is the case this exists for: it accepted only a PDF, so a PNG logo or a scanned
     * signature — the two things people most want to stamp — could not be used at all. The
     * document itself is still validated with {@link #pdf}; only the artwork is permissive.
     */
    public ArtworkKind pdfOrImage(MultipartFile file, String partName) {
        requirePresent(file, partName);
        byte[] head = sniff(file);
        if (indexOf(head, PDF) >= 0) return ArtworkKind.PDF;
        if (looksLikeImage(head)) return ArtworkKind.IMAGE;
        throw ApiException.invalidFile("'" + describe(file)
                + "' is not a PDF or a supported image. Use a .pdf, or JPG, PNG, GIF, BMP or WebP.");
    }

    // ── Page counts (applied once a document is open) ──────────────────────────────

    /** Caps pages for structural tools. */
    public void pageCount(int pages) {
        if (pages > properties.getMaxPages()) {
            throw ApiException.tooLarge("This PDF has " + pages + " pages; the limit is "
                    + properties.getMaxPages() + ". Split it first, then try again.");
        }
    }

    /** Caps pages for tools that rasterise every page — far costlier, so a lower ceiling. */
    public void renderablePageCount(int pages) {
        if (pages > properties.getMaxRenderPages()) {
            throw ApiException.tooLarge("This tool renders every page and is limited to "
                    + properties.getMaxRenderPages() + " pages; this PDF has " + pages
                    + ". Split it first, then try again.");
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────────

    private void requirePresent(MultipartFile file, String partName) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Required file '" + partName + "' is missing or empty.");
        }
    }

    private void requireCount(List<MultipartFile> files, int minimum, String partName) {
        if (files == null || files.size() < minimum) {
            throw ApiException.badRequest("This tool needs at least " + minimum
                    + " file" + (minimum == 1 ? "" : "s") + " in '" + partName + "'.");
        }
        if (files.size() > properties.getMaxFiles()) {
            throw ApiException.tooLarge("Too many files: " + files.size()
                    + ". The limit is " + properties.getMaxFiles() + " per request.");
        }
    }

    /** Reads the leading bytes without consuming the part — callers still read it in full later. */
    private byte[] sniff(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(SNIFF_BYTES);
        } catch (IOException e) {
            throw ApiException.invalidFile("'" + describe(file) + "' could not be read.");
        }
    }

    private boolean looksLikeImage(byte[] head) {
        return startsWith(head, PNG)
                || startsWith(head, JPEG)
                || startsWith(head, GIF87)
                || startsWith(head, GIF89)
                || startsWith(head, BMP)
                || startsWith(head, TIFF_LE)
                || startsWith(head, TIFF_BE)
                || (startsWith(head, RIFF) && regionMatches(head, 8, WEBP));
    }

    private static boolean startsWith(byte[] data, byte[] signature) {
        return regionMatches(data, 0, signature);
    }

    private static boolean regionMatches(byte[] data, int offset, byte[] signature) {
        if (data.length < offset + signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if (data[offset + i] != signature[i]) return false;
        }
        return true;
    }

    /** First index of {@code signature} within {@code data}, or -1. */
    private static int indexOf(byte[] data, byte[] signature) {
        outer:
        for (int i = 0; i <= data.length - signature.length; i++) {
            for (int j = 0; j < signature.length; j++) {
                if (data[i + j] != signature[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** A safe label for messages — never echoes the raw client filename back verbatim. */
    private String describe(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) return "upload";
        String trimmed = name.length() > 60 ? name.substring(0, 60) + "…" : name;
        return trimmed.replaceAll("[\\r\\n<>\"]", "");
    }
}

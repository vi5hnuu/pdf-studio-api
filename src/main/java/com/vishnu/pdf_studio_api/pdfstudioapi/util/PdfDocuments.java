package com.vishnu.pdf_studio_api.pdfstudioapi.util;

import com.vishnu.pdf_studio_api.pdfstudioapi.exception.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.io.RandomAccessStreamCache.StreamCacheCreateFunction;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Opens PDFs with a memory strategy chosen from the document's size.
 *
 * <p>PDFBox is unchanged — this only decides <em>where</em> it keeps its working data. Small files
 * stay entirely in memory, which is what they should do: spilling a 200 KB document to disk would
 * be slower for no benefit. At or above {@code app.load.scratch-file-threshold-bytes}, PDFBox is
 * given a temp-file stream cache so a large or page-heavy document no longer has to fit in the heap.
 *
 * <p>Reading from a {@link RandomAccessReadBufferedFile} rather than a {@code byte[]} also lets
 * PDFBox seek within the file instead of requiring a full in-heap copy of the input.
 */
public final class PdfDocuments {

    /**
     * Size at or above which PDFBox is given a temp-file stream cache.
     *
     * <p>Static because {@code PdfTools} is a dependency-free static utility and cannot take an
     * injected value; {@code PdfMemoryConfigurer} overwrites it from {@code app.load.*} at startup
     * so it stays configurable.
     */
    private static volatile long defaultThresholdBytes = 8L * 1024 * 1024;

    /**
     * Hard ceiling on pages, enforced here because <em>every</em> PDF load in the service passes
     * through this class. Enforcing it at the individual tools instead left the heaviest ones
     * (compress, grayscale, the Office conversions) uncapped, since they load inside {@code PdfTools}
     * rather than through the service's open helper.
     */
    private static volatile int maxPages = 2000;

    private PdfDocuments() {}

    public static long defaultThreshold() {
        return defaultThresholdBytes;
    }

    public static void setDefaultThreshold(long bytes) {
        if (bytes > 0) defaultThresholdBytes = bytes;
    }

    public static int maxPages() {
        return maxPages;
    }

    public static void setMaxPages(int pages) {
        if (pages > 0) maxPages = pages;
    }

    /** Loads using the configured default threshold. */
    public static PDDocument load(Path path) throws IOException {
        return load(path, defaultThresholdBytes, null);
    }

    /**
     * Loads a PDF from a temp file, choosing the memory strategy by size.
     *
     * @param path      the file to read
     * @param threshold size at or above which the temp-file stream cache is used
     */
    public static PDDocument load(Path path, long threshold) throws IOException {
        return load(path, threshold, null);
    }

    /** As {@link #load(Path, long)}, for an encrypted document. */
    public static PDDocument load(Path path, long threshold, String password) throws IOException {
        long size = java.nio.file.Files.size(path);
        StreamCacheCreateFunction cache = cacheFor(size, threshold);
        RandomAccessReadBufferedFile source = new RandomAccessReadBufferedFile(path.toFile());
        PDDocument document;
        try {
            document = password == null
                    ? Loader.loadPDF(source, cache)
                    : Loader.loadPDF(source, password, cache);
        } catch (IOException | RuntimeException e) {
            source.close(); // Loader only adopts the source once it succeeds
            throw e;
        }

        // Page count is only knowable after parsing, so the cap is applied here — the one point
        // every tool's input passes through.
        int pages = document.getNumberOfPages();
        if (pages > maxPages) {
            document.close();
            throw ApiException.tooLarge("This PDF has " + pages + " pages; the limit is "
                    + maxPages + ". Split it first, then try again.");
        }
        return document;
    }

    /**
     * Temp-file cache for large documents, memory for small ones.
     *
     * <p>Returning the memory cache below the threshold is what keeps the common case as fast as it
     * was before: only documents big enough for the heap to matter pay for disk.
     */
    public static StreamCacheCreateFunction cacheFor(long sizeBytes, long threshold) {
        return sizeBytes >= threshold
                ? IOUtils.createTempFileOnlyStreamCache()
                : IOUtils.createMemoryOnlyStreamCache();
    }
}

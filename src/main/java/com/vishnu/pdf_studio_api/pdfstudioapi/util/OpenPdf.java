package com.vishnu.pdf_studio_api.pdfstudioapi.util;

import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * A PDF opened from a temp file, owning both the document and the file it is backed by.
 *
 * <p>The two must be closed together and in order — the document reads from the file, so deleting
 * the file first would pull the ground out from under it. Bundling them into one
 * {@link AutoCloseable} means a single {@code try}-with-resources at each call site cannot get that
 * ordering wrong or leak the temp file on an exception path.
 */
public record OpenPdf(TempFiles.Handle handle, PDDocument document) implements AutoCloseable {

    @Override
    public void close() {
        try {
            document.close();
        } catch (Exception ignored) {
            // Closing a document whose parse failed can throw; the temp file must still go.
        } finally {
            handle.close();
        }
    }
}

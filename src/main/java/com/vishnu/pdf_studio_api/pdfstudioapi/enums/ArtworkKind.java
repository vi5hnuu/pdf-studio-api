package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

/**
 * What a piece of uploaded artwork actually is, decided by magic bytes rather than by the
 * filename or {@code Content-Type} the client claimed.
 *
 * <p>Determined once during validation and carried to the tool, so nothing sniffs the same bytes
 * twice and no tool has to guess.
 */
public enum ArtworkKind {
    /** A PDF, drawn by importing its first page as a form XObject. */
    PDF,
    /** A raster image (JPEG, PNG, GIF, BMP, WebP, TIFF). */
    IMAGE
}

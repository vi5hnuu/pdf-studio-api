package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import lombok.Getter;

/**
 * The page an imported image is placed on.
 *
 * <p>Separate from {@link PageSizePreset} because {@link #MATCH_IMAGE} is only meaningful when
 * there is an image to match; the resize-page tool must not be offered it.
 */
@Getter
public enum ImagePageSize {

    /** A standard page, with the image fitted inside it. */
    A4(PageSizePreset.A4),
    LETTER(PageSizePreset.LETTER),
    LEGAL(PageSizePreset.LEGAL),

    /**
     * One point per pixel — the original behaviour, kept for callers that relied on it.
     *
     * <p>Rarely what anyone wants: a 4000x3000 photo becomes a page roughly 55 by 42 inches, and a
     * mixed set of photos produces a document where every page is a different size.
     */
    MATCH_IMAGE(null);

    private final PageSizePreset preset;

    ImagePageSize(PageSizePreset preset) {
        this.preset = preset;
    }
}

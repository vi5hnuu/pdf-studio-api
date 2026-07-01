package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import lombok.Getter;

/** Standard page sizes, in PDF points (1/72 inch). */
@Getter
public enum PageSizePreset {
    A4(595f, 842f),
    LETTER(612f, 792f),
    LEGAL(612f, 1008f);

    private final float width;
    private final float height;

    PageSizePreset(float width, float height) {
        this.width = width;
        this.height = height;
    }
}

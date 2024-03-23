package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import lombok.Getter;


public enum CompressionLevel {
    EXTREME(50,0.3f), // Low quality, high compression
    RECOMMENDED(150,0.5f), // Balanced quality and compression
    LOW(300,0.7f); // High quality, minimal compression

    @Getter
    private final int dpi;

    @Getter
    private final float quality;

    CompressionLevel(int dpi,float quality) {
        this.dpi = dpi;
        this.quality=quality;
    }
}

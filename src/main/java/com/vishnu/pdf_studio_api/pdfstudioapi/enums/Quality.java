package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import lombok.Getter;


@Getter
public enum Quality {
    LOW(72),
    MEDIUM(150),
    HIGH(300);

    private final int dpi;

    Quality(int dpi) {
        this.dpi = dpi;
    }
}

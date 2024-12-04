package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
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

    @JsonCreator
    public static Quality fromDpi(int dpi) {
        for (Quality quality : Quality.values()) {
            if (quality.getDpi() != dpi) continue;
            return quality;
        }
        throw new IllegalArgumentException("No Quality found for dpi: " + dpi);
    }

    @JsonValue
    public int toValue() {
        return this.dpi;
    }
}

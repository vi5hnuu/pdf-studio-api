package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
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

    /**
     * Reads a quality from either its name or its DPI.
     *
     * <p>Accepting the DPI is not cosmetic. Jackson's default for an integer token is to treat it
     * as an <em>ordinal</em>, and the app sent {@code "quality": 72 | 150 | 300} — all far outside
     * the legal index range — so every pdf-to-jpg request failed with a 400 before the controller
     * body ran. That is why the tool failed identically in all three modes: it never got as far as
     * looking at {@code single} or {@code direction}. The app now sends the name, but builds
     * already on people's phones do not, and they keep working because of this.
     *
     * @throws IllegalArgumentException with the accepted values, so a 400 can say what to send.
     */
    @JsonCreator
    public static Quality from(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) {
            int dpi = node.asInt();
            for (Quality quality : values()) {
                if (quality.dpi == dpi) return quality;
            }
            throw new IllegalArgumentException(
                    "Unknown quality dpi " + dpi + "; expected one of 72, 150, 300.");
        }
        String text = node.asText("").trim();
        for (Quality quality : values()) {
            if (quality.name().equalsIgnoreCase(text)) return quality;
        }
        throw new IllegalArgumentException(
                "Unknown quality '" + text + "'; expected LOW, MEDIUM or HIGH.");
    }
}

package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;


@Getter
public enum PageNoType {
    ONLY_X("X"),
    PAGE_X_OF_Y("page_X_of_Y"),
    PAGE_X("page_X");

    private final String type;

    PageNoType(String type){
        this.type=type;
    }

    /**
     * Reads a page-number format from either its name or its {@link #type} label.
     *
     * <p>Jackson matches enum names, case-sensitively, and the app sent the label — {@code "X"},
     * {@code "page_X_of_Y"}, {@code "page_X"} — none of which is a constant name. So every
     * page-numbers request failed with a 400. The app now sends the name; the label is still
     * accepted so builds already installed keep working.
     *
     * @throws IllegalArgumentException with the accepted values, so a 400 can say what to send.
     */
    @JsonCreator
    public static PageNoType from(String value) {
        if (value == null) return null;
        String text = value.trim();
        for (PageNoType candidate : values()) {
            if (candidate.name().equalsIgnoreCase(text) || candidate.type.equalsIgnoreCase(text)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "Unknown page number type '" + text + "'; expected ONLY_X, PAGE_X or PAGE_X_OF_Y.");
    }
}

package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import lombok.Getter;


@Getter
public enum SplitType {
    SPLIT_BY_RANGE("SPLIT_BY_RANGE"),
    FIXED_RANGE("FIXED_RANGE"),
    DELETE_PAGES("DELETE_PAGES"),
    EXTRACT_ALL_PAGES("EXTRACT_ALL_PAGES");

    private final String type;
    SplitType(String type) {
        this.type=type;
    }
}

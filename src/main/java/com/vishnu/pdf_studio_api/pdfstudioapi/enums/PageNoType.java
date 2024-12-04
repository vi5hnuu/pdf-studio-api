package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
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


    @JsonCreator
    public static PageNoType fromType(String type) {
        for (PageNoType pageNoType : PageNoType.values()) {
            if (!pageNoType.type.equalsIgnoreCase(type)) continue;
            return pageNoType;
        }
        throw new IllegalArgumentException("No PageNoType found for type: " + type);
    }

    @JsonValue
    public String toValue() {
        return this.type;
    }
}

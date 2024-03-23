package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

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
}

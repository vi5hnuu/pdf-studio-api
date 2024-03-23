package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import lombok.Getter;


@Getter
public enum Postion {
    START("START"),
    CENTER("CENTER"),
    END("END");
    final String position;
    Postion(String position) {
        this.position=position;
    }
}

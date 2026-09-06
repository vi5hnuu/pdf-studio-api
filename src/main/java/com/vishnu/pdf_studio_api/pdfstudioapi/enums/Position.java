package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import lombok.Getter;


@Getter
public enum Position {
    START("START"),
    CENTER("CENTER"),
    END("END");
    final String position;
    Position(String position) {
        this.position=position;
    }
}

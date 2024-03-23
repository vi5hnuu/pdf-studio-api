package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import lombok.Getter;


public enum FontStyle {
    REGUALR("REGULAR"),
    BOLD("BOLD"),
    ITALIC("ITALOC");

    final String type;
    FontStyle(String type) {
        this.type=type;
    }
}

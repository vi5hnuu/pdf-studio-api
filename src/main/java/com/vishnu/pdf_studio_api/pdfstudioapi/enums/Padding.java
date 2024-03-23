package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;


@Getter
public class Padding {
    private final Float top;
    private final Float left;
    private final Float bottom;
    private final Float right;

    public Padding(Float top, Float left, Float bottom, Float right) {
        this.top = top != null ? top : 0;
        this.left = left != null ? left : 0;
        this.bottom = bottom != null ? bottom : 0;
        this.right = right != null ? right : 0;
    }

    public Padding() {
        this.top = 0f;
        this.left = 0f;
        this.bottom = 0f;
        this.right = 0f;
    }
}

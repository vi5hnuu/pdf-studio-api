package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import lombok.Getter;


@Getter
public enum Direction {
    HORIZONTAL("HORIZONTAL"),
    VERTICAL("VERTICAL");

    final String direction;
    Direction(String direction){
           this.direction=direction;
    }

    @Override
    public String toString() {
        return direction;
    }
}

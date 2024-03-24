package com.vishnu.pdf_studio_api.pdfstudioapi.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.awt.*;


@Getter
@Setter
@AllArgsConstructor
public class RangeModel {
    private final int from;
    private final int to;
}

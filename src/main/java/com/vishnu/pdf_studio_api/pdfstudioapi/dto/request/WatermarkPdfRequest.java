package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.Postion;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.ColorModel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class WatermarkPdfRequest {
    private String outFileName;
    private String text = "CONFIDENTIAL";
    private Integer fontSize = 48;
    private ColorModel color;
    private Float opacity = 0.3f;
    private Double angle = 45.0;
    private Postion verticalPosition = Postion.CENTER;
    private Postion horizontalPosition = Postion.CENTER;
    private Integer fromPage = 0;
    private Integer toPage;
}

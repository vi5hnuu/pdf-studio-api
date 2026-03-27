package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.ColorModel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class HeaderFooterRequest {
    private String outFileName;
    private String headerText;
    private String footerText;
    private Integer fontSize;
    private ColorModel color;
    private Standard14Fonts.FontName fontName;
    private Integer fromPage;
    private Integer toPage;
    private Float topPadding;
    private Float bottomPadding;
}

package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CompressionLevel;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.Padding;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PageNoType;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.Position;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.ColorModel;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.*;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;

import java.awt.*;
import java.util.List;

@Getter
@Setter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PageNumbersRequest {
    private String outFileName;
    private PageNoType pageNoType;
    private Integer size;//14 default
    private ColorModel fillColor;
    private Position verticalPosition;
    private Position horizontalPosition;
    private Padding padding; //default 0
    private Integer fromPage; //default 0
    private Integer toPage; //default lengthOfPDF
    private Standard14Fonts.FontName fontName;

    public PageNumbersRequest(PageNoType pageNoType,Integer size, ColorModel fillColor, Position verticalPosition, Position horizontalPosition, Padding padding, Integer fromPage, Integer toPage, Standard14Fonts.FontName fontName) {
        this.pageNoType = pageNoType!=null ? pageNoType : PageNoType.ONLY_X;
        this.size = (size != null) ? size : 14;
        this.fillColor = fillColor!=null ? fillColor : ColorModel.BLACK;
        this.verticalPosition = verticalPosition!=null ? verticalPosition : Position.END;
        this.horizontalPosition = horizontalPosition!=null ? horizontalPosition : Position.CENTER;
        this.padding = padding!=null ? padding : new Padding();
        this.fromPage = (fromPage != null) ? fromPage : 0;
        this.toPage = toPage;
        this.fontName=fontName!=null ? fontName : Standard14Fonts.FontName.COURIER;
    }
    public PageNumbersRequest() {
        this.pageNoType =PageNoType.ONLY_X;
        this.size =14;
        this.fillColor = ColorModel.BLACK;
        this.verticalPosition = Position.END;
        this.horizontalPosition = Position.CENTER;
        this.padding =new Padding();
        this.fromPage = 0;
        this.toPage = null;
        this.fontName=Standard14Fonts.FontName.COURIER;
    }
}

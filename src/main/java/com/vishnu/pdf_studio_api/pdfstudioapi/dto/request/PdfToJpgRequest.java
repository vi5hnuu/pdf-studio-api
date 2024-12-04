package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CompressionLevel;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.Direction;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.Quality;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

@Getter
@Setter
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PdfToJpgRequest {
    private String outFileName; //zip name/single image name is single=true
    private Quality quality;
    private Boolean single;
    private Direction direction;//if image is single -> join horizontally or vertically
    private Integer imageGap; // gap if single=true

    public PdfToJpgRequest(){}
}

package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class BorderImageRequest {
    private String outFileName;
    // Border thickness in pixels.
    private Integer width = 20;
    // Border colour (0–255 each).
    private Integer r = 0;
    private Integer g = 0;
    private Integer b = 0;
}

package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RedactPdfRequest {
    private String outFileName;
    private List<RedactRegion> regions;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class RedactRegion {
        private int page;       // 0-indexed page number
        private float x;        // left edge in PDF points, top-left origin (client coords)
        private float y;        // top edge in PDF points, top-left origin (client coords)
        private float width;
        private float height;
    }
}

package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.ImageFit;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.Placement;
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

    /**
     * One rectangle to remove.
     *
     * <p>Give the box as fractions of its own page. The point fields are still read when no
     * fractions are sent, but they are the wrong unit here: a client that converts what the user
     * drew into points has to pick a page size to convert against, and it only has one — so on a
     * document whose pages differ, bars drawn on one page land somewhere else on another. For a
     * redaction tool that means content the user meant to remove staying in the file.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class RedactRegion {
        private int page;       // 0-indexed page number
        private float x;        // left edge in PDF points, top-left origin (client coords)
        private float y;        // top edge in PDF points, top-left origin (client coords)
        private float width;
        private float height;

        /** The box as a fraction of its page (0.0-1.0), top-left origin, as displayed. */
        @JsonProperty("x_frac")
        private Float xFrac;
        @JsonProperty("y_frac")
        private Float yFrac;
        private Float widthFrac;
        private Float heightFrac;

        /** The fractional box, or {@code null} when only point coordinates were sent. */
        public Placement placement() {
            return Placement.of(xFrac, yFrac, widthFrac, heightFrac, 0f, ImageFit.STRETCH);
        }
    }
}

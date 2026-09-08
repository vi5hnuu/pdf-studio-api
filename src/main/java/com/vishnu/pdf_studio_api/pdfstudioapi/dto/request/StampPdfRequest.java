package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.ImageFit;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.Placement;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Stamping artwork onto a range of pages.
 *
 * <p>The artwork part may be a one-page PDF or an image. Position and size are optional: send none
 * of them and the stamp is drawn at its natural size at the page origin, which is what this tool
 * did before it could be positioned, so existing callers are unaffected.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class StampPdfRequest {
    private String outFileName;
    private Float opacity;
    /** 0-indexed, inclusive. Absent means the first page / the last page. */
    private Integer fromPage;
    private Integer toPage;

    // Fractions of the page (0.0–1.0), origin top-left. @JsonProperty is required on the x/y pair:
    // Lombok's getXFrac() yields the bean property "XFrac", which SnakeCaseStrategy renders as
    // "xfrac" rather than "x_frac". Same quirk documented on PlaceImageRequest.
    @JsonProperty("x_frac")
    private Float xFrac;
    @JsonProperty("y_frac")
    private Float yFrac;
    private Float widthFrac;
    private Float heightFrac;

    /** Clockwise degrees about the centre of the placement. */
    private Float rotation;

    /** Defaults to {@link ImageFit#CONTAIN}, so a box of the wrong shape does not distort artwork. */
    private ImageFit fit = ImageFit.CONTAIN;

    /** The requested geometry, or {@code null} when no box was sent. */
    public Placement placement() {
        return Placement.of(xFrac, yFrac, widthFrac, heightFrac, rotation, fit);
    }
}

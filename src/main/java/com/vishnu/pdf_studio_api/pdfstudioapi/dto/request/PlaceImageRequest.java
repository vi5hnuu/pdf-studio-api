package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Position and size of an image to place on a specific PDF page.
 * All coordinate/size values are fractions of the page dimensions (0.0–1.0).
 * x_frac / y_frac: top-left position of the image relative to the page.
 * width_frac / height_frac: image dimensions relative to the page.
 * page: 0-indexed page number.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PlaceImageRequest {
    private String outFileName;
    private int page;
    // @JsonProperty needed: Lombok generates getXFrac() → Java bean property "XFrac"
    // (two consecutive uppercase chars suppress decapitalization) → SnakeCaseStrategy produces
    // "xfrac" instead of the intended "x_frac". Explicit annotation overrides this.
    @JsonProperty("x_frac")
    private float xFrac;
    @JsonProperty("y_frac")
    private float yFrac;
    private float widthFrac;
    private float heightFrac;
}

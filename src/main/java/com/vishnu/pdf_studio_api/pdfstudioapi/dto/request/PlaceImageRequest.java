package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.ImageFit;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.Placement;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Position and size of an image to place on one or more PDF pages.
 *
 * <p>All coordinate/size values are fractions of the page dimensions (0.0–1.0), with
 * {@code x_frac}/{@code y_frac} giving the top-left corner of the box.
 *
 * <p>{@link #page} is the original single-page field and still works; {@link #pages} places the
 * same image on several pages in one request, which is what signing a whole document needs.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PlaceImageRequest {
    private String outFileName;
    /** 0-indexed single page. Ignored when {@link #pages} is supplied. */
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

    /** 0-indexed pages. Takes precedence over {@link #page} when non-empty. */
    private List<Integer> pages;

    /** Clockwise degrees about the centre of the placement. */
    private Float rotation;

    /**
     * Defaults to {@link ImageFit#CONTAIN}.
     *
     * <p>The image used to be drawn at exactly the requested width and height with no reference to
     * its own proportions, so any box of a different shape distorted it. Clients that deliberately
     * want that — the app offers a "Free" resize mode — send {@link ImageFit#STRETCH}.
     */
    private ImageFit fit = ImageFit.CONTAIN;

    /** The requested geometry. Never null: this tool has always required a box. */
    public Placement placement() {
        return Placement.of(xFrac, yFrac, widthFrac, heightFrac, rotation, fit);
    }

    /** The pages to draw on, preferring the multi-page field and falling back to {@link #page}. */
    public List<Integer> targetPages() {
        return pages == null || pages.isEmpty() ? List.of(page) : pages;
    }
}

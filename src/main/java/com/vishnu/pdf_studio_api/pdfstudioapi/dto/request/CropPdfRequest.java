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
public class CropPdfRequest {
    private String outFileName;

    /**
     * Margins in points, measured from each edge.
     *
     * <p>Kept for callers that have not been updated. They are the wrong unit for this tool: a
     * margin can only be measured against one page, so a document whose pages differ in size is
     * cropped correctly on that page and wrongly on every other. Prefer {@link #keepXFrac} and
     * its siblings.
     */
    private Float marginTop;
    private Float marginBottom;
    private Float marginLeft;
    private Float marginRight;

    /**
     * The area to keep, as a fraction of each page (0.0–1.0), origin top-left, in the
     * orientation the page is displayed in.
     *
     * <p>All four or none. Being a proportion of whatever page it lands on, this is correct on
     * every page of a document regardless of their sizes or rotations.
     */
    @JsonProperty("keep_x_frac")
    private Float keepXFrac;
    @JsonProperty("keep_y_frac")
    private Float keepYFrac;
    private Float keepWidthFrac;
    private Float keepHeightFrac;
    /**
     * 0-indexed pages to apply this to. Empty or absent applies to the whole document, which is
     * what this tool always did.
     */
    private List<Integer> pages;

    /** The kept area, or {@code null} when the caller sent point margins instead. */
    public Placement keep() {
        // STRETCH: a crop is the exact box asked for, never fitted to something else's shape.
        return Placement.of(keepXFrac, keepYFrac, keepWidthFrac, keepHeightFrac, 0f, ImageFit.STRETCH);
    }
}

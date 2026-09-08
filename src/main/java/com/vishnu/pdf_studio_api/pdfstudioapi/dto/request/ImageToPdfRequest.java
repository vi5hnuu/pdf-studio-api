package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.ImagePageSize;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PageOrientation;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * Turning images into a PDF.
 *
 * <p>Each image used to become a page one point per pixel, so a phone photo produced a page around
 * 55 by 42 inches and a mixed set produced a document with a different page size on every page —
 * a file no one could print. Pages are now a real size by default, with the image fitted inside
 * them; {@link ImagePageSize#MATCH_IMAGE} restores the old behaviour for anyone who wanted it.
 */
@Getter
@Setter
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ImageToPdfRequest {
    private String outFileName; //zip name/single image name is single=true

    private ImagePageSize pageSize = ImagePageSize.A4;
    private PageOrientation orientation = PageOrientation.AUTO;

    /** White space around the image, in points (72 = 1 inch). Off by default. */
    private Float marginPt = 0f;

    public ImageToPdfRequest(){}
}

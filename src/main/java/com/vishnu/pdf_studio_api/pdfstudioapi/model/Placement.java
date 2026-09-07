package com.vishnu.pdf_studio_api.pdfstudioapi.model;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.ImageFit;

import java.awt.geom.Rectangle2D;

/**
 * Where a piece of artwork goes on a page, expressed independently of the page's real size.
 *
 * <p>Callers cannot know a document's page dimensions before uploading it, so every position and
 * size here is a <b>fraction of the page</b> (0.0–1.0) with the origin at the <b>top-left</b>,
 * matching how a client's own preview is laid out. {@link #resolve} converts that to PDF user
 * space, whose origin is bottom-left, so no caller has to.
 *
 * <p>This is shared by stamping and image placement, which were two separate half-implementations
 * of the same idea: stamping could pick pages but not a position, placement could pick a position
 * but only one page, and only placement considered the artwork's proportions — badly. Resolving
 * the geometry in one place is what keeps them consistent.
 */
public record Placement(float xFrac, float yFrac, float widthFrac, float heightFrac,
                        /** Clockwise degrees about the centre of the placement. */
                        float rotation, ImageFit fit) {

    public Placement {
        if (fit == null) fit = ImageFit.CONTAIN;
    }

    /**
     * Builds a placement, or {@code null} when the caller did not specify a box.
     *
     * <p>A null return is meaningful rather than an error: it is how a caller says "draw the
     * artwork at its natural size where it naturally falls", which is what stamping did before it
     * could be positioned and what it must keep doing for callers that send no box.
     */
    public static Placement of(Float xFrac, Float yFrac, Float widthFrac, Float heightFrac,
                               Float rotation, ImageFit fit) {
        if (xFrac == null || yFrac == null || widthFrac == null || heightFrac == null) return null;
        if (widthFrac <= 0 || heightFrac <= 0) return null;
        return new Placement(xFrac, yFrac, widthFrac, heightFrac,
                rotation == null ? 0f : rotation, fit);
    }

    /**
     * This box narrowed to the artwork's own proportions, still expressed in display fractions.
     *
     * <p>Separating the fit from the final resolve is what lets a rotated page be handled: the
     * artwork has to be fitted against the page <em>as the user saw it</em>, and only then mapped
     * back into the page's stored coordinates. Doing both at once would fit against the wrong
     * pair of dimensions on a page turned on its side.
     */
    public Placement fitted(float pageWidth, float pageHeight, float contentWidth, float contentHeight) {
        if (fit != ImageFit.CONTAIN || contentWidth <= 0 || contentHeight <= 0 || pageWidth <= 0 || pageHeight <= 0) {
            return this;
        }
        float boxWidth = widthFrac * pageWidth;
        float boxHeight = heightFrac * pageHeight;
        float scale = Math.min(boxWidth / contentWidth, boxHeight / contentHeight);
        float drawWidth = contentWidth * scale;
        float drawHeight = contentHeight * scale;

        return new Placement(
                xFrac + (boxWidth - drawWidth) / 2f / pageWidth,
                yFrac + (boxHeight - drawHeight) / 2f / pageHeight,
                drawWidth / pageWidth,
                drawHeight / pageHeight,
                rotation,
                // Already the artwork's own shape, so the resolve must not fit it a second time.
                ImageFit.STRETCH);
    }

    /**
     * Re-expresses this box, drawn on the page <em>as displayed</em>, in the page's own
     * unrotated coordinates.
     *
     * <p>A page carrying {@code /Rotate 90} is shown turned on its side, so a box the user drew
     * over the top-left of what they saw is not the top-left of the page as stored. Every client
     * previews the rotated page — that is the whole point of a preview — so fractions arrive in
     * display space and have to be turned back before they mean anything to PDFBox.
     *
     * @param pageRotation the page's {@code /Rotate}, in degrees; anything not a multiple of 90
     *                     is treated as no rotation, which is what PDFBox does with it
     */
    public Placement forPageRotation(int pageRotation) {
        int turns = Math.floorMod(pageRotation / 90, 4);
        if (pageRotation % 90 != 0 || turns == 0) return this;

        return switch (turns) {
            // Display (u,v) relates to page-space (a,b) by u = 1-b, v = a.
            case 1 -> new Placement(yFrac, 1 - xFrac - widthFrac, heightFrac, widthFrac, rotation, fit);
            case 2 -> new Placement(1 - xFrac - widthFrac, 1 - yFrac - heightFrac, widthFrac, heightFrac, rotation, fit);
            // u = b, v = 1-a.
            default -> new Placement(1 - yFrac - heightFrac, xFrac, heightFrac, widthFrac, rotation, fit);
        };
    }

    /**
     * Converts this placement to a rectangle in PDF user space.
     *
     * @param pageWidth      page width in points
     * @param pageHeight     page height in points
     * @param contentWidth   the artwork's natural width, in any unit — only its ratio to
     *                       {@code contentHeight} is used
     * @param contentHeight  the artwork's natural height, same unit
     * @return the rectangle to draw into, bottom-left origin. Under {@link ImageFit#CONTAIN} it is
     *         the largest rectangle of the artwork's own proportions that fits the requested box,
     *         centred in it; under {@link ImageFit#STRETCH} it is the requested box exactly.
     */
    public Rectangle2D.Float resolve(float pageWidth, float pageHeight,
                                     float contentWidth, float contentHeight) {
        float boxWidth = widthFrac * pageWidth;
        float boxHeight = heightFrac * pageHeight;

        float drawWidth = boxWidth;
        float drawHeight = boxHeight;
        if (fit == ImageFit.CONTAIN && contentWidth > 0 && contentHeight > 0) {
            float scale = Math.min(boxWidth / contentWidth, boxHeight / contentHeight);
            drawWidth = contentWidth * scale;
            drawHeight = contentHeight * scale;
        }

        // Centre the artwork in the requested box, so shrinking it to keep its proportions does
        // not also shift it towards a corner.
        float left = xFrac * pageWidth + (boxWidth - drawWidth) / 2f;
        // yFrac measures down from the top of the page; PDF measures up from the bottom.
        float bottom = pageHeight - (yFrac * pageHeight) - boxHeight + (boxHeight - drawHeight) / 2f;

        return new Rectangle2D.Float(left, bottom, drawWidth, drawHeight);
    }
}

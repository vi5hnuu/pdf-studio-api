package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Marks to add to a PDF as real annotation objects.
 *
 * <p>Geometry arrives as <b>fractions of the page with the origin at the top-left</b> — the same
 * convention {@link com.vishnu.pdf_studio_api.pdfstudioapi.model.Placement} uses — so the client
 * never has to know a page's size in points, and a document with mixed page sizes needs no special
 * handling. The conversion to PDF's bottom-left point space happens in one place, in
 * {@code PdfAnnotator}.
 *
 * <p>The whole document's marks come in one request. The annotate tool used to stamp one rasterised
 * image per annotated page, re-uploading the growing PDF each time.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AnnotatePdfRequest {
    private String outFileName;
    private List<AnnotationSpec> annotations;

    /**
     * Bakes the marks into the page content and removes the annotation objects.
     *
     * <p>Off by default, because a real annotation is the better artefact: vector, listed as a
     * comment, and removable. But not every renderer draws annotations — Pdfium, which the app's
     * own preview uses, ignores them entirely — so a user who wants the marks to show up
     * <em>everywhere</em>, or to be permanent, needs this. Done in the same request so it costs
     * neither an extra round trip nor an extra charge.
     */
    private boolean flatten = false;

    /**
     * One mark.
     *
     * <p>Deliberately one flat shape rather than a polymorphic hierarchy: the fields that apply
     * depend on {@code type}, and a Jackson type hierarchy for six small variants costs more than
     * it explains. {@code PdfAnnotator} switches on {@code type} and reads what that type needs.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class AnnotationSpec {
        /** ink | highlight | square | circle | line | free_text | note */
        private String type;

        /** 0-indexed. */
        private Integer page;

        /** #RRGGBB. Transparency travels in {@link #opacity}, because a PDF annotation carries it
         *  in /CA rather than in the colour. */
        private String color;
        private String fillColor;
        private Float opacity;

        /** [x, y, width, height] as page fractions, origin top-left. Used by the box-shaped types. */
        private List<Float> rect;

        /** Flattened [[x, y], …] page fractions, for ink and highlighter strokes. */
        private List<List<Float>> points;

        /** Line endpoints, as page fractions. */
        private List<Float> from;
        private List<Float> to;
        private Boolean arrow;

        /** Fraction of the page's shorter side, so a stroke keeps its weight on any page size. */
        private Float strokeWidth;

        private String text;
        /** Fraction of the page height. */
        private Float fontSize;
        private Boolean bold;
    }
}

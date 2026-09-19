package com.vishnu.pdf_studio_api.pdfstudioapi.utils;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.AnnotatePdfRequest.AnnotationSpec;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.PdfDocuments;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationCircle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFreeText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationInk;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLine;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds marks to a PDF as <b>real annotation objects</b>.
 *
 * <p>The annotate tool used to render its marks to a PNG and stamp that over the page. The result
 * was pixels: soft when zoomed, invisible to a viewer's comment list, and impossible to edit or
 * remove afterwards. These are `/Ink`, `/Highlight`, `/Square`, `/Circle`, `/Line`, `/FreeText`
 * and `/Text` objects, so they stay vector, show up as comments in Acrobat and Preview, and can be
 * taken off again.
 *
 * <p>Separate from {@link PdfTools} for the same reason {@link PdfInspector} is: one concern per
 * file, and this one is the only place that knows how a mark becomes an annotation.
 *
 * <p><b>Appearance streams matter.</b> Most viewers draw an annotation from its `/AP` stream and
 * ignore the geometry if there isn't one — an annotation without an appearance is simply invisible
 * outside Acrobat. PDFBox ships an appearance handler for each of these types, so every annotation
 * gets {@link PDAnnotation#constructAppearances(PDDocument)} called on it before saving.
 */
public final class PdfAnnotator {

    private PdfAnnotator() {
    }

    /** Whoever the marks are attributed to in a viewer's comment list. */
    private static final String AUTHOR = "PDF Craft";

    public static byte[] annotate(Path pdfPath, List<AnnotationSpec> specs) throws IOException {
        return annotate(pdfPath, specs, false);
    }

    /** As {@link #annotate(Path, List)}, optionally baking the marks into the page content. */
    public static byte[] annotate(Path pdfPath, List<AnnotationSpec> specs, boolean flatten)
            throws IOException {
        try (PDDocument doc = PdfDocuments.load(pdfPath);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            if (specs != null) {
                for (AnnotationSpec spec : specs) {
                    int pageIndex = spec.getPage() == null ? 0 : spec.getPage();
                    if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) continue;
                    PDPage page = doc.getPage(pageIndex);
                    PDAnnotation annotation = build(spec, page);
                    if (annotation == null) continue;

                    annotation.setPrinted(true);
                    if (spec.getOpacity() != null) {
                        annotation.getCOSObject().setFloat("CA", clamp01(spec.getOpacity()));
                    }
                    // Attach first, *then* build the appearance. The handlers create the
                    // appearance stream against the document and reach back through the
                    // annotation; generating it while the annotation was still detached wrote a
                    // stream the saved file had no path to, and reloading it logged
                    // "Can't dereference COSObject" and produced an annotation with no
                    // appearance — present in the file and invisible in every viewer.
                    page.getAnnotations().add(annotation);
                    annotation.constructAppearances(doc);
                }
            }

            if (flatten) flattenAnnotations(doc);

            doc.save(out, CompressParameters.NO_COMPRESSION);
            return out.toByteArray();
        }
    }

    /**
     * Draws every annotation's appearance into the page content and removes the annotation.
     *
     * <p>PDFBox's {@code PDAcroForm.flatten()} only handles form fields, so markup annotations
     * need doing by hand. Each appearance stream is a form XObject with its own bounding box and
     * matrix; the transform below maps that box onto the annotation's rectangle, which is what the
     * PDF specification says a viewer does when it draws the annotation. Getting that mapping
     * wrong is the classic failure — the mark lands in the corner of the page at the wrong size.
     */
    private static void flattenAnnotations(PDDocument doc) throws IOException {
        for (PDPage page : doc.getPages()) {
            List<PDAnnotation> annotations = page.getAnnotations();
            if (annotations.isEmpty()) continue;

            try (PDPageContentStream cs = new PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                for (PDAnnotation annotation : annotations) {
                    PDAppearanceStream appearance = annotation.getNormalAppearanceStream();
                    if (appearance == null) continue;

                    PDRectangle rect = annotation.getRectangle();
                    PDRectangle bbox = appearance.getBBox();
                    if (rect == null || bbox == null || bbox.getWidth() == 0 || bbox.getHeight() == 0) {
                        continue;
                    }

                    // The appearance's own matrix first, then fit the transformed box into /Rect.
                    Matrix matrix = appearance.getMatrix();
                    java.awt.geom.Rectangle2D transformed = bbox.transform(matrix).getBounds2D();
                    if (transformed.getWidth() == 0 || transformed.getHeight() == 0) continue;

                    float sx = (float) (rect.getWidth() / transformed.getWidth());
                    float sy = (float) (rect.getHeight() / transformed.getHeight());

                    cs.saveGraphicsState();
                    cs.transform(Matrix.getTranslateInstance(rect.getLowerLeftX(), rect.getLowerLeftY()));
                    cs.transform(Matrix.getScaleInstance(sx, sy));
                    cs.transform(Matrix.getTranslateInstance((float) -transformed.getX(),
                            (float) -transformed.getY()));
                    cs.drawForm(appearance);
                    cs.restoreGraphicsState();
                }
            }
            // The marks are part of the page now; leaving the objects behind would draw them twice.
            page.setAnnotations(new ArrayList<>());
        }
    }

    private static PDAnnotation build(AnnotationSpec spec, PDPage page) throws IOException {
        String type = spec.getType() == null ? "" : spec.getType();
        return switch (type) {
            case "ink" -> ink(spec, page);
            case "highlight" -> highlight(spec, page);
            case "square" -> square(spec, page, false);
            case "circle" -> square(spec, page, true);
            case "line" -> line(spec, page);
            case "free_text" -> freeText(spec, page);
            case "note" -> note(spec, page);
            default -> null;
        };
    }

    // ── Types ────────────────────────────────────────────────────────────────────

    private static PDAnnotation ink(AnnotationSpec spec, PDPage page) {
        float[][] pts = points(spec, page);
        if (pts.length == 0) return null;

        PDAnnotationInk ink = new PDAnnotationInk();
        ink.setInkList(new float[][]{flatten(pts)});
        ink.setColor(rgb(spec.getColor()));
        ink.setRectangle(padded(boundsOf(pts), strokeWidth(spec, page)));

        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(strokeWidth(spec, page));
        ink.setBorderStyle(border);
        ink.setContents(spec.getText());
        ink.setTitlePopup(AUTHOR);
        return ink;
    }

    /**
     * A highlighter stroke, as a real `/Highlight`.
     *
     * <p>`/Highlight` is defined by QuadPoints — the quadrilaterals it covers — because it normally
     * marks selected text. A freehand drag is not a text selection, so each segment of the stroke
     * becomes one quad, expanded perpendicular to the segment by half the stroke width. The result
     * multiplies over the text underneath rather than painting a translucent band on top of it,
     * which is the difference between a highlighter and a grey marker: the words stay readable, and
     * two overlapping strokes do not darken.
     */
    private static PDAnnotation highlight(AnnotationSpec spec, PDPage page) {
        float[][] pts = points(spec, page);
        if (pts.length == 0) return null;
        float half = Math.max(strokeWidth(spec, page), 1f) / 2f;

        List<Float> quads = new ArrayList<>();
        if (pts.length == 1) {
            addQuad(quads, pts[0][0] - half, pts[0][1] - half, pts[0][0] + half, pts[0][1] + half);
        } else {
            for (int i = 1; i < pts.length; i++) {
                float x1 = pts[i - 1][0], y1 = pts[i - 1][1];
                float x2 = pts[i][0], y2 = pts[i][1];
                // Axis-aligned quad around the segment. A rotated quad would hug a diagonal
                // stroke more tightly, but viewers render the union either way and this keeps
                // the maths — and the failure modes — simple.
                addQuad(quads,
                        Math.min(x1, x2) - half, Math.min(y1, y2) - half,
                        Math.max(x1, x2) + half, Math.max(y1, y2) + half);
            }
        }

        PDAnnotationHighlight h = new PDAnnotationHighlight();
        h.setQuadPoints(toArray(quads));
        h.setColor(rgb(spec.getColor()));
        h.setRectangle(padded(boundsOf(pts), half * 2));
        h.setContents(spec.getText());
        h.setTitlePopup(AUTHOR);
        return h;
    }

    private static PDAnnotation square(AnnotationSpec spec, PDPage page, boolean ellipse) {
        PDRectangle r = rect(spec.getRect(), page);
        if (r == null) return null;

        var annotation = ellipse ? new PDAnnotationCircle() : new PDAnnotationSquare();
        annotation.setRectangle(r);
        annotation.setColor(rgb(spec.getColor()));
        if (spec.getFillColor() != null) annotation.setInteriorColor(rgb(spec.getFillColor()));

        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(strokeWidth(spec, page));
        annotation.setBorderStyle(border);
        annotation.setContents(spec.getText());
        annotation.setTitlePopup(AUTHOR);
        return annotation;
    }

    private static PDAnnotation line(AnnotationSpec spec, PDPage page) {
        if (spec.getFrom() == null || spec.getTo() == null) return null;
        float[] a = pointOf(spec.getFrom(), page);
        float[] b = pointOf(spec.getTo(), page);

        PDAnnotationLine line = new PDAnnotationLine();
        line.setLine(new float[]{a[0], a[1], b[0], b[1]});
        line.setColor(rgb(spec.getColor()));
        if (Boolean.TRUE.equals(spec.getArrow())) {
            line.setEndPointEndingStyle(PDAnnotationLine.LE_OPEN_ARROW);
        }

        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(strokeWidth(spec, page));
        line.setBorderStyle(border);

        // The rectangle must contain the line *and* its arrowhead, or viewers clip it.
        float pad = Math.max(strokeWidth(spec, page) * 4, 8);
        line.setRectangle(new PDRectangle(
                Math.min(a[0], b[0]) - pad, Math.min(a[1], b[1]) - pad,
                Math.abs(b[0] - a[0]) + pad * 2, Math.abs(b[1] - a[1]) + pad * 2));
        line.setContents(spec.getText());
        line.setTitlePopup(AUTHOR);
        return line;
    }

    private static PDAnnotation freeText(AnnotationSpec spec, PDPage page) {
        PDRectangle r = rect(spec.getRect(), page);
        if (r == null) return null;

        float size = spec.getFontSize() == null
                ? 12f
                : Math.max(4f, spec.getFontSize() * page.getMediaBox().getHeight());

        PDAnnotationFreeText text = new PDAnnotationFreeText();
        text.setRectangle(r);
        text.setContents(spec.getText() == null ? "" : spec.getText());
        // /DA is the appearance string the FreeText handler draws with: font, size, colour.
        float[] c = rgbComponents(spec.getColor());
        text.setDefaultAppearance(String.format(
                "/Helv %.2f Tf %.3f %.3f %.3f rg", size, c[0], c[1], c[2]));
        text.setTitlePopup(AUTHOR);
        return text;
    }

    private static PDAnnotation note(AnnotationSpec spec, PDPage page) {
        PDRectangle r = rect(spec.getRect(), page);
        if (r == null) return null;

        PDAnnotationText note = new PDAnnotationText();
        note.setName(PDAnnotationText.NAME_COMMENT);
        // A /Text annotation is an icon of a fixed size wherever it is dropped; only its corner
        // is meaningful, so the incoming box is used for position and given the icon's size.
        note.setRectangle(new PDRectangle(r.getLowerLeftX(), r.getUpperRightY() - 20, 20, 20));
        note.setContents(spec.getText() == null ? "" : spec.getText());
        note.setColor(rgb(spec.getColor()));
        note.setOpen(false);
        note.setTitlePopup(AUTHOR);
        return note;
    }

    // ── Geometry ─────────────────────────────────────────────────────────────────
    // Everything arrives as a fraction of the page with the origin top-left; PDF user space has
    // its origin bottom-left. That flip happens here and nowhere else.

    private static float[] pointOf(List<Float> xy, PDPage page) {
        PDRectangle box = page.getMediaBox();
        return new float[]{
                xy.get(0) * box.getWidth(),
                box.getHeight() - xy.get(1) * box.getHeight()
        };
    }

    private static float[][] points(AnnotationSpec spec, PDPage page) {
        if (spec.getPoints() == null || spec.getPoints().isEmpty()) return new float[0][];
        List<float[]> out = new ArrayList<>();
        for (List<Float> p : spec.getPoints()) {
            if (p == null || p.size() < 2) continue;
            out.add(pointOf(p, page));
        }
        return out.toArray(new float[0][]);
    }

    private static PDRectangle rect(List<Float> r, PDPage page) {
        if (r == null || r.size() < 4) return null;
        PDRectangle box = page.getMediaBox();
        float x = r.get(0) * box.getWidth();
        float w = r.get(2) * box.getWidth();
        float h = r.get(3) * box.getHeight();
        // The incoming y is the box's *top*; PDRectangle wants its bottom.
        float y = box.getHeight() - (r.get(1) * box.getHeight()) - h;
        return new PDRectangle(x, y, w, h);
    }

    /** A stroke width given as a fraction of the page's shorter side, in points. */
    private static float strokeWidth(AnnotationSpec spec, PDPage page) {
        if (spec.getStrokeWidth() == null) return 2f;
        PDRectangle box = page.getMediaBox();
        return Math.max(0.5f, spec.getStrokeWidth() * Math.min(box.getWidth(), box.getHeight()));
    }

    private static float[] boundsOf(float[][] pts) {
        float minX = pts[0][0], maxX = pts[0][0], minY = pts[0][1], maxY = pts[0][1];
        for (float[] p : pts) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    /** The annotation rectangle has to clear the stroke, or viewers clip the ends of it. */
    private static PDRectangle padded(float[] b, float strokeWidth) {
        float pad = Math.max(strokeWidth, 1f);
        return new PDRectangle(b[0] - pad, b[1] - pad,
                (b[2] - b[0]) + pad * 2, (b[3] - b[1]) + pad * 2);
    }

    private static float[] flatten(float[][] pts) {
        float[] flat = new float[pts.length * 2];
        for (int i = 0; i < pts.length; i++) {
            flat[i * 2] = pts[i][0];
            flat[i * 2 + 1] = pts[i][1];
        }
        return flat;
    }

    private static void addQuad(List<Float> quads, float x1, float y1, float x2, float y2) {
        // QuadPoints order is top-left, top-right, bottom-left, bottom-right.
        quads.add(x1); quads.add(y2);
        quads.add(x2); quads.add(y2);
        quads.add(x1); quads.add(y1);
        quads.add(x2); quads.add(y1);
    }

    private static float[] toArray(List<Float> values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
        return out;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float[] rgbComponents(String hex) {
        Color c = parse(hex);
        return new float[]{c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f};
    }

    private static PDColor rgb(String hex) {
        return new PDColor(rgbComponents(hex), PDDeviceRGB.INSTANCE);
    }

    private static Color parse(String hex) {
        if (hex == null || hex.isBlank()) return Color.RED;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            return new Color(Integer.parseInt(h, 16));
        } catch (NumberFormatException e) {
            return Color.RED;
        }
    }
}

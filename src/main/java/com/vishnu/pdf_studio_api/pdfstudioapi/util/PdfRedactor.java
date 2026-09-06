package com.vishnu.pdf_studio_api.pdfstudioapi.util;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes page content that falls inside a set of rectangles, rather than covering it up.
 *
 * <p>Painting a black rectangle over a name hides it from a reader and from nobody else: the
 * glyphs are still in the content stream, so selecting the area, running {@code pdftotext},
 * or opening the file in any editor recovers the original text in full. A redaction tool that
 * only draws rectangles is worse than none, because it tells the user the content is gone.
 *
 * <p>This deletes the content instead. Text is removed glyph by glyph, so only what the user
 * actually covered disappears; the glyphs that survive keep their positions because the
 * advance of each removed glyph is re-inserted as a {@code TJ} adjustment. Images that fall
 * entirely within a rectangle are dropped. The caller still paints the rectangles afterwards,
 * so the result looks like a redaction as well as being one.
 *
 * <p>All coordinates are in PDF user space (bottom-left origin), matching {@code PDPage}.
 *
 * <h2>Limits</h2>
 * An image only partly covered by a rectangle is left in place — masking part of it would mean
 * re-encoding the image — so callers should treat a partially covered image as not redacted.
 */
public final class PdfRedactor {

    private PdfRedactor() {
    }

    /**
     * Deletes text and fully covered images inside {@code regions} from {@code page}.
     *
     * @param regions rectangles in PDF user space; empty or null leaves the page untouched
     */
    public static void removeContent(PDDocument document, PDPage page, List<Rectangle2D.Float> regions)
            throws IOException {

        if (regions == null || regions.isEmpty()) return;

        Locator locator = new Locator(regions);
        locator.processPage(page);
        if (locator.removals.isEmpty() && locator.imagesToDrop.isEmpty()) return;

        List<Object> tokens = new PDFStreamParser(page).parse();

        List<Object> rewritten = new Rewriter(locator).apply(tokens);

        PDStream updated = new PDStream(document);
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            new ContentStreamWriter(buffer).writeTokens(rewritten);
            try (var out = updated.createOutputStream(org.apache.pdfbox.cos.COSName.FLATE_DECODE)) {
                out.write(buffer.toByteArray());
            }
        }
        page.setContents(updated);
    }

    /** One glyph run to delete: a byte span inside one string of one show-text operator. */
    private record Span(int start, int end, float advanceThousandths) {
    }

    /** Identifies a single string operand: the n-th show-text operator, its m-th string. */
    private record StringKey(int operatorIndex, int stringIndex) {
    }

    // ── Pass 1: find what has to go ───────────────────────────────────────────

    /**
     * Walks the page's operators tracking text and graphics state, and records the byte spans
     * of every glyph whose box intersects a redaction rectangle.
     */
    private static final class Locator extends PDFStreamEngine {

        private final List<Rectangle2D.Float> regions;
        private final Map<StringKey, List<Span>> removals = new HashMap<>();
        private final Set<Integer> imagesToDrop = new HashSet<>();

        /** Counts show-text operators so pass two can line up with the same ones. */
        private int operatorIndex = -1;
        private int stringIndex;
        /** Counts image-drawing operators, for the same reason. */
        private int imageIndex = -1;

        private List<int[]> byteSpans = List.of();
        private int glyphInString;
        private List<Span> pendingForString;

        Locator(List<Rectangle2D.Float> regions) {
            this.regions = regions;
            RenderingOperators.registerOn(this);
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            String name = operator.getName();
            if (isShowText(name)) {
                operatorIndex++;
                stringIndex = -1;
            } else if ("Do".equals(name)) {
                imageIndex++;
                noteImageIfCovered(operands);
            }
            super.processOperator(operator, operands);
        }

        @Override
        protected void showText(byte[] string) throws IOException {
            stringIndex++;
            byteSpans = decodeSpans(string, getGraphicsState().getTextState().getFont());
            glyphInString = 0;
            pendingForString = new ArrayList<>();

            super.showText(string);

            if (!pendingForString.isEmpty()) {
                removals.put(new StringKey(operatorIndex, stringIndex), pendingForString);
            }
            pendingForString = null;
        }

        @Override
        protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement)
                throws IOException {

            int index = glyphInString++;
            if (pendingForString != null && index < byteSpans.size() && intersectsRegion(textRenderingMatrix, font, displacement)) {
                int[] span = byteSpans.get(index);
                pendingForString.add(new Span(span[0], span[1], compensationFor(displacement)));
            }
            super.showGlyph(textRenderingMatrix, font, code, displacement);
        }

        @Override
        public void showForm(org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) {
            // A form XObject has its own content stream, which this rewriter does not reach.
            // Descending would find glyphs whose spans belong to a stream we never rewrite, so
            // they would be reported as removed and silently survive. Not descending keeps the
            // report honest; nested text is left to the rectangle drawn over it.
        }

        /**
         * Drops an image only when a rectangle covers it completely.
         *
         * <p>An image is drawn into the unit square by the current transformation matrix, so
         * the CTM is its placement on the page. Masking part of an image would mean decoding
         * and re-encoding it, and dropping one that is only partly covered would erase visible
         * content the user did not select — so a partial overlap is left alone.
         */
        private void noteImageIfCovered(List<COSBase> operands) {
            if (operands.isEmpty() || !(operands.get(0) instanceof COSName name)) return;
            PDResources resources = getResources();
            if (resources == null || !resources.isImageXObject(name)) return;

            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            float width = Math.abs(ctm.getScalingFactorX());
            float height = Math.abs(ctm.getScalingFactorY());
            if (width <= 0 || height <= 0) return;

            Rectangle2D.Float bounds = new Rectangle2D.Float(
                    Math.min(ctm.getTranslateX(), ctm.getTranslateX() + ctm.getScalingFactorX()),
                    Math.min(ctm.getTranslateY(), ctm.getTranslateY() + ctm.getScalingFactorY()),
                    width, height);

            for (Rectangle2D.Float region : regions) {
                if (region.contains(bounds)) {
                    imagesToDrop.add(imageIndex);
                    return;
                }
            }
        }

        private boolean intersectsRegion(Matrix trm, PDFont font, Vector displacement) {
            float scaleY = trm.getScalingFactorY();
            // Ascent and descent describe where ink actually goes relative to the baseline.
            // The font's bounding box is far looser — it spans the tallest accent to the
            // deepest descender of any glyph in the font, roughly 1.2em, which reaches into
            // the neighbouring lines and would delete text the user never covered.
            float top = ascent(font) * scaleY;
            float bottom = descent(font) * scaleY; // negative: below the baseline

            float x = trm.getTranslateX();
            float baseline = trm.getTranslateY();
            float width = Math.max(displacement.getX() * trm.getScalingFactorX(), 0.1f);
            float height = Math.max(top - bottom, 0.1f);

            Rectangle2D.Float glyph = new Rectangle2D.Float(x, baseline + bottom, width, height);
            for (Rectangle2D.Float region : regions) {
                if (region.intersects(glyph)) return true;
            }
            return false;
        }

        /**
         * The TJ number that reproduces the advance of a glyph being deleted, so the text that
         * follows it on the same line does not slide left into the gap.
         *
         * <p>A TJ element {@code a} shifts by {@code -a/1000 * fontSize * horizontalScale},
         * while the glyph itself would have advanced
         * {@code (w * fontSize + charSpacing + wordSpacing) * horizontalScale}. Solving for
         * {@code a} keeps the two identical.
         */
        private float compensationFor(Vector displacement) {
            PDTextState text = getGraphicsState().getTextState();
            float fontSize = text.getFontSize();
            if (fontSize == 0) return 0;
            float advance = displacement.getX() * fontSize + text.getCharacterSpacing();
            return -(advance / fontSize) * 1000f;
        }
    }

    /** Registers the operators the locator needs in order to track state. */
    private static final class RenderingOperators {
        static void registerOn(PDFStreamEngine engine) {
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.state.Concatenate(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.state.Save(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.state.Restore(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.state.SetMatrix(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.BeginText(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.EndText(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.MoveText(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.MoveTextSetLeading(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.NextLine(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.SetCharSpacing(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.SetFontAndSize(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.SetTextHorizontalScaling(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.SetTextLeading(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.SetTextRenderingMode(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.SetTextRise(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.SetWordSpacing(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.ShowText(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.ShowTextAdjusted(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.ShowTextLine(engine));
            engine.addOperator(new org.apache.pdfbox.contentstream.operator.text.ShowTextLineAndSpace(engine));
        }
    }

    // ── Pass 2: write the page back without it ────────────────────────────────

    private static final class Rewriter {

        private final Locator located;
        private int operatorIndex = -1;
        private int imageIndex = -1;

        Rewriter(Locator located) {
            this.located = located;
        }

        List<Object> apply(List<Object> tokens) {
            List<Object> out = new ArrayList<>(tokens.size());
            List<COSBase> operands = new ArrayList<>();

            for (Object token : tokens) {
                if (!(token instanceof Operator operator)) {
                    operands.add((COSBase) token);
                    continue;
                }

                String name = operator.getName();
                if ("Do".equals(name)) {
                    imageIndex++;
                    if (located.imagesToDrop.contains(imageIndex)) {
                        operands.clear();
                        continue; // the image was entirely inside a rectangle
                    }
                }
                if (isShowText(name)) {
                    operatorIndex++;
                    out.addAll(rewriteShowText(operator, operands));
                    operands.clear();
                    continue;
                }

                out.addAll(operands);
                out.add(operator);
                operands.clear();
            }
            out.addAll(operands);
            return out;
        }

        /**
         * Rebuilds one show-text operator without its redacted glyphs.
         *
         * <p>Everything becomes a {@code TJ}, because that is the only form that can carry the
         * spacing adjustments that hold the surviving glyphs in place. The line-moving variants
         * ({@code '} and {@code "}) keep their side effects by emitting the equivalent explicit
         * operators first, so nothing about the text position changes.
         */
        private List<Object> rewriteShowText(Operator operator, List<COSBase> operands) {
            List<Object> out = new ArrayList<>();
            String name = operator.getName();

            // ' is "next line, then show"; " additionally sets word and character spacing.
            if ("\"".equals(name) && operands.size() >= 3) {
                out.add(operands.get(0));
                out.add(Operator.getOperator("Tw"));
                out.add(operands.get(1));
                out.add(Operator.getOperator("Tc"));
            }
            if ("'".equals(name) || "\"".equals(name)) {
                out.add(Operator.getOperator("T*"));
            }

            List<COSBase> strings = showTextOperands(name, operands);
            COSArray rebuilt = new COSArray();
            int stringIndex = -1;
            boolean changed = false;

            for (COSBase operand : strings) {
                if (!(operand instanceof COSString string)) {
                    rebuilt.add(operand); // a TJ spacing number, kept as-is
                    continue;
                }
                stringIndex++;
                List<Span> spans = located.removals.get(new StringKey(operatorIndex, stringIndex));
                if (spans == null || spans.isEmpty()) {
                    rebuilt.add(string);
                    continue;
                }
                changed = true;
                appendWithout(rebuilt, string.getBytes(), spans);
            }

            if (!changed && !"'".equals(name) && !"\"".equals(name)) {
                out.addAll(operands);
                out.add(operator);
                return out;
            }

            out.add(rebuilt);
            out.add(Operator.getOperator("TJ"));
            return out;
        }

        /** Splits one string around the removed spans, inserting each one's advance. */
        private void appendWithout(COSArray target, byte[] bytes, List<Span> spans) {
            int cursor = 0;
            float pendingAdjustment = 0;

            for (Span span : spans) {
                if (span.start() > cursor) {
                    if (pendingAdjustment != 0) {
                        target.add(new COSFloat(pendingAdjustment));
                        pendingAdjustment = 0;
                    }
                    target.add(new COSString(slice(bytes, cursor, span.start())));
                }
                pendingAdjustment += span.advanceThousandths();
                cursor = Math.max(cursor, span.end());
            }

            if (pendingAdjustment != 0) target.add(new COSFloat(pendingAdjustment));
            if (cursor < bytes.length) target.add(new COSString(slice(bytes, cursor, bytes.length)));
        }
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private static boolean isShowText(String operator) {
        return "Tj".equals(operator) || "TJ".equals(operator)
                || "'".equals(operator) || "\"".equals(operator);
    }

    /** The operands that actually carry text, whichever show-text form was used. */
    private static List<COSBase> showTextOperands(String name, List<COSBase> operands) {
        if (operands.isEmpty()) return List.of();
        COSBase last = operands.get(operands.size() - 1);
        if ("TJ".equals(name) && last instanceof COSArray array) {
            return new ArrayList<>(array.toList());
        }
        return List.of(last);
    }

    /**
     * Byte offsets of each glyph code within a show-text string.
     *
     * <p>A code is one byte in a simple font and usually two in a composite one, so the only
     * dependable way to cut a single glyph out of the string is to ask the font itself where
     * each code begins and ends.
     */
    private static List<int[]> decodeSpans(byte[] string, PDFont font) {
        List<int[]> spans = new ArrayList<>();
        if (font == null) return spans;

        try (InputStream in = new ByteArrayInputStream(string)) {
            int start = 0;
            while (in.available() > 0) {
                font.readCode(in);
                int end = string.length - in.available();
                spans.add(new int[]{start, end});
                start = end;
            }
        } catch (IOException e) {
            return List.of(); // undecodable: leave the string alone rather than corrupt it
        }
        return spans;
    }

    private static byte[] slice(byte[] source, int from, int to) {
        byte[] copy = new byte[to - from];
        System.arraycopy(source, from, copy, 0, copy.length);
        return copy;
    }

    /** Height of the tallest ink above the baseline, in text-space units (1 = one em). */
    private static float ascent(PDFont font) {
        try {
            var descriptor = font.getFontDescriptor();
            if (descriptor != null && descriptor.getAscent() > 0) return descriptor.getAscent() / 1000f;
            return font.getBoundingBox().getUpperRightY() / 1000f;
        } catch (Exception e) {
            return 0.75f; // typical ascent; a sane default beats failing the redaction
        }
    }

    /** Depth of the lowest ink below the baseline (negative), in text-space units. */
    private static float descent(PDFont font) {
        try {
            var descriptor = font.getFontDescriptor();
            if (descriptor != null && descriptor.getDescent() < 0) return descriptor.getDescent() / 1000f;
            return font.getBoundingBox().getLowerLeftY() / 1000f;
        } catch (Exception e) {
            return -0.22f;
        }
    }
}

package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.AnnotatePdfRequest;
import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.AnnotatePdfRequest.AnnotationSpec;
import com.vishnu.pdf_studio_api.pdfstudioapi.utils.PdfAnnotator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationInk;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers turning the annotate tool's marks into real PDF annotations.
 *
 * <p>The failure mode worth pinning is not "the annotation is missing" — it is "the annotation is
 * present but nothing draws it". Most viewers render a markup annotation from its `/AP` appearance
 * stream and ignore the geometry when there isn't one, so an annotation saved without appearances
 * is in the file, readable by our own inspectors, and completely invisible to the user. Every test
 * here therefore asserts the appearance stream as well as the object.
 */
class PdfAnnotatorTest {

    private static final int A4_W = 595;
    private static final int A4_H = 842;

    private Path onePagePdf() throws Exception {
        Path path = Files.createTempFile("annotate-test-", ".pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(A4_W, A4_H)));
            doc.save(path.toFile());
        }
        return path;
    }

    private static AnnotationSpec spec(String type) {
        AnnotationSpec s = new AnnotationSpec();
        s.setType(type);
        s.setPage(0);
        s.setColor("#FF0000");
        s.setStrokeWidth(0.005f);
        return s;
    }

    private static List<Float> rect(float x, float y, float w, float h) {
        return List.of(x, y, w, h);
    }

    /**
     * Annotates a fresh page and hands the reloaded annotations to [check] <b>while the document is
     * still open</b>.
     *
     * <p>That last part is not incidental. An earlier version of this helper returned the
     * annotation list and let the document close first; every appearance-stream assertion then
     * failed with a null, because a `PDAppearanceStream` is backed by the document's stream cache
     * and reading one after close gives nothing back. The bug was in the test, and it looked
     * exactly like the product bug it was written to catch — so the shape of this helper is the
     * fix.
     */
    private void withAnnotations(List<AnnotationSpec> specs, ThrowingConsumer check) throws Exception {
        Path pdf = onePagePdf();
        try {
            byte[] out = PdfAnnotator.annotate(pdf, specs);
            try (PDDocument doc = Loader.loadPDF(out)) {
                check.accept(List.copyOf(doc.getPage(0).getAnnotations()));
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer {
        void accept(List<PDAnnotation> annotations) throws Exception;
    }

    @Test
    void aPenStrokeBecomesAnInkAnnotationThatSomethingActuallyDraws() throws Exception {
        AnnotationSpec ink = spec("ink");
        ink.setPoints(List.of(List.of(0.1f, 0.1f), List.of(0.5f, 0.5f), List.of(0.8f, 0.2f)));

        withAnnotations(List.of(ink), annots -> {
            assertEquals(1, annots.size());
            assertInstanceOf(PDAnnotationInk.class, annots.get(0));
            assertNotNull(annots.get(0).getNormalAppearanceStream(),
                    "an annotation with no appearance stream is invisible in most viewers");
        });
    }

    @Test
    void aHighlighterStrokeBecomesARealHighlightWithOneQuadPerSegment() throws Exception {
        // Not an Ink annotation with alpha: /Highlight multiplies over the text underneath, which
        // is why the words stay readable and two overlapping strokes do not darken.
        AnnotationSpec h = spec("highlight");
        h.setPoints(List.of(List.of(0.1f, 0.5f), List.of(0.4f, 0.5f), List.of(0.7f, 0.5f)));

        withAnnotations(List.of(h), annots -> {
            assertEquals(1, annots.size());
            PDAnnotationHighlight highlight =
                    assertInstanceOf(PDAnnotationHighlight.class, annots.get(0));
            // Two segments, four corners each, two floats per corner.
            assertEquals(16, highlight.getQuadPoints().length);
            assertNotNull(highlight.getNormalAppearanceStream());
        });
    }

    @Test
    void geometryArrivesAsPageFractionsAndComesOutInPointsWithTheOriginFlipped() throws Exception {
        // The client never knows the page size. A box at 10% from the left and 20% from the *top*
        // of an A4 page has to land at x=59.5 and, because PDF space counts from the bottom,
        // y = 842 - 0.2*842 - 0.3*842 = 421.
        AnnotationSpec square = spec("square");
        square.setRect(rect(0.1f, 0.2f, 0.5f, 0.3f));

        withAnnotations(List.of(square), annots -> {
            PDRectangle r = assertInstanceOf(PDAnnotationSquare.class, annots.get(0)).getRectangle();

            // PDFBox's square appearance handler grows /Rect outwards by half the border width,
            // since the rectangle has to contain the stroke it draws. So the box is asserted as
            // "the requested geometry, expanded by at most half a stroke" rather than exactly.
            float half = 0.005f * A4_W / 2;
            float expectedX = 0.1f * A4_W;
            float expectedY = A4_H - 0.2f * A4_H - 0.3f * A4_H;

            assertEquals(expectedX, r.getLowerLeftX(), half + 0.5);
            assertEquals(expectedY, r.getLowerLeftY(), half + 0.5);
            assertEquals(0.5f * A4_W, r.getWidth(), half * 2 + 0.5);
            assertEquals(0.3f * A4_H, r.getHeight(), half * 2 + 0.5);
            // The flip is the part that would be silently wrong: a box 20% from the top must not
            // come out 20% from the bottom.
            assertTrue(r.getLowerLeftY() > A4_H / 2f - 100,
                    "a box near the top of the page came out near the bottom — the flip is wrong");
        });
    }

    @Test
    void opacityTravelsAsCaSoAViewerHonoursIt() throws Exception {
        AnnotationSpec square = spec("square");
        square.setRect(rect(0.1f, 0.1f, 0.2f, 0.2f));
        square.setOpacity(0.35f);

        withAnnotations(List.of(square),
                annots -> assertEquals(0.35f, annots.get(0).getCOSObject().getFloat("CA"), 0.001));
    }

    @Test
    void aStickyNoteBecomesACollapsibleTextAnnotationCarryingItsComment() throws Exception {
        AnnotationSpec note = spec("note");
        note.setRect(rect(0.5f, 0.5f, 0.1f, 0.1f));
        note.setText("check this figure");

        withAnnotations(List.of(note), annots -> {
            PDAnnotationText text = assertInstanceOf(PDAnnotationText.class, annots.get(0));
            assertEquals("check this figure", text.getContents());
            assertFalse(text.getOpen(), "a note should start collapsed, not covering the page");
            assertNotNull(text.getNormalAppearanceStream());
        });
    }

    @Test
    void everySupportedTypeProducesAnAnnotationAndAnAppearance() throws Exception {
        AnnotationSpec ink = spec("ink");
        ink.setPoints(List.of(List.of(0.1f, 0.1f), List.of(0.2f, 0.2f)));
        AnnotationSpec highlight = spec("highlight");
        highlight.setPoints(List.of(List.of(0.1f, 0.3f), List.of(0.4f, 0.3f)));
        AnnotationSpec square = spec("square");
        square.setRect(rect(0.1f, 0.4f, 0.2f, 0.1f));
        AnnotationSpec circle = spec("circle");
        circle.setRect(rect(0.4f, 0.4f, 0.2f, 0.1f));
        AnnotationSpec line = spec("line");
        line.setFrom(List.of(0.1f, 0.6f));
        line.setTo(List.of(0.6f, 0.6f));
        line.setArrow(true);
        AnnotationSpec freeText = spec("free_text");
        freeText.setRect(rect(0.1f, 0.7f, 0.5f, 0.05f));
        freeText.setText("a note on the page");
        freeText.setFontSize(0.02f);
        AnnotationSpec note = spec("note");
        note.setRect(rect(0.8f, 0.8f, 0.05f, 0.05f));
        note.setText("hi");

        withAnnotations(List.of(ink, highlight, square, circle, line, freeText, note), annots -> {
            assertEquals(7, annots.size());
            for (PDAnnotation a : annots) {
                assertNotNull(a.getNormalAppearanceStream(),
                        a.getSubtype() + " has no appearance stream, so no viewer would draw it");
                assertTrue(a.isPrinted(), a.getSubtype() + " would vanish when the page is printed");
            }
        });
    }

    @Test
    void anUnknownTypeIsSkippedRatherThanFailingTheWholeRequest() throws Exception {
        AnnotationSpec good = spec("square");
        good.setRect(rect(0.1f, 0.1f, 0.2f, 0.2f));
        AnnotationSpec bad = spec("something-we-do-not-have");

        withAnnotations(List.of(good, bad), annots ->
                assertEquals(1, annots.size(), "one bad mark should not lose the user the other nine"));
    }

    @Test
    void aMarkOnAPageThatDoesNotExistIsSkipped() throws Exception {
        AnnotationSpec offPage = spec("square");
        offPage.setPage(7);
        offPage.setRect(rect(0.1f, 0.1f, 0.2f, 0.2f));

        withAnnotations(List.of(offPage), annots -> assertEquals(0, annots.size()));
    }

    @Test
    void flatteningBakesTheMarksIntoThePageAndLeavesNoAnnotationsBehind() throws Exception {
        // Real annotations are the better artefact, but not every renderer draws them — Pdfium,
        // which the app's own preview uses, ignores them completely, so a user who flattens
        // expects marks that show up everywhere. Both halves matter: the content has to gain the
        // mark *and* the annotation object has to go, or it is drawn twice in viewers that do
        // render annotations.
        AnnotationSpec ink = spec("ink");
        ink.setPoints(List.of(List.of(0.1f, 0.1f), List.of(0.5f, 0.5f)));
        AnnotationSpec square = spec("square");
        square.setRect(rect(0.2f, 0.6f, 0.4f, 0.1f));

        Path pdf = onePagePdf();
        try {
            long plainLength;
            try (PDDocument doc = Loader.loadPDF(PdfAnnotator.annotate(pdf, List.of()))) {
                plainLength = doc.getPage(0).getContents().readAllBytes().length;
            }

            byte[] flat = PdfAnnotator.annotate(pdf, List.of(ink, square), true);
            try (PDDocument doc = Loader.loadPDF(flat)) {
                assertEquals(0, doc.getPage(0).getAnnotations().size(),
                        "a flattened mark left its annotation behind, so viewers that draw "
                                + "annotations would render it twice");
                assertTrue(doc.getPage(0).getContents().readAllBytes().length > plainLength,
                        "nothing was added to the page content, so the mark was simply lost");
            }

            // …and the default still produces annotations rather than baking them in.
            try (PDDocument doc = Loader.loadPDF(PdfAnnotator.annotate(pdf, List.of(ink, square)))) {
                assertEquals(2, doc.getPage(0).getAnnotations().size());
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void theRequestDeserializesFromExactlyWhatTheAppSends() throws Exception {
        // The app builds this JSON by hand. A snake_case mismatch does not fail — it silently
        // leaves the field null, so a stroke width or a colour would quietly become the default.
        String sent = """
                {"out_file_name":"annotated_doc","annotations":[
                  {"type":"ink","page":0,"color":"#FF0000","opacity":1.0,
                   "stroke_width":0.004,"points":[[0.1,0.2],[0.3,0.4]]},
                  {"type":"free_text","page":1,"color":"#0000FF","rect":[0.1,0.2,0.3,0.05],
                   "text":"hello","font_size":0.02,"bold":true},
                  {"type":"line","page":0,"color":"#00FF00","from":[0.1,0.1],"to":[0.5,0.5],
                   "arrow":true,"stroke_width":0.003},
                  {"type":"square","page":0,"color":"#000000","fill_color":"#FFFF00",
                   "rect":[0.1,0.1,0.2,0.2]}
                ]}""";

        AnnotatePdfRequest req = new ObjectMapper().readValue(sent, AnnotatePdfRequest.class);

        assertEquals("annotated_doc", req.getOutFileName());
        assertEquals(4, req.getAnnotations().size());

        AnnotationSpec ink = req.getAnnotations().get(0);
        assertEquals(0.004f, ink.getStrokeWidth(), 0.0001);
        assertEquals(2, ink.getPoints().size());
        assertEquals(0.1f, ink.getPoints().get(0).get(0), 0.0001);

        AnnotationSpec text = req.getAnnotations().get(1);
        assertEquals("hello", text.getText());
        assertEquals(0.02f, text.getFontSize(), 0.0001);
        assertEquals(Boolean.TRUE, text.getBold());
        assertEquals(1, text.getPage());

        assertEquals(Boolean.TRUE, req.getAnnotations().get(2).getArrow());
        assertEquals("#FFFF00", req.getAnnotations().get(3).getFillColor());
    }
}

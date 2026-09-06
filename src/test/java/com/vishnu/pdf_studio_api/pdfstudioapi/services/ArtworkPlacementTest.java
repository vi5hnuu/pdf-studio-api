package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.LoadProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.UploadProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.ImageFit;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.ImagePageSize;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PageOrientation;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.Placement;
import com.vishnu.pdf_studio_api.pdfstudioapi.validation.UploadValidator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers stamping and image placement, which used to be two half-built versions of one feature.
 *
 * <p>Three things were wrong and are asserted here directly rather than through "the response was
 * not empty": a stamp could only be a PDF, a placed image could only go on one page, and the drawn
 * size came from the requested box alone, so any box of a different shape distorted the image.
 * The last one is only visible by reading back the transformation the content stream actually
 * applied, which is what {@link DrawnImages} does.
 */
class ArtworkPlacementTest {

    /** The sample image is deliberately wide: distortion is obvious in a 2:1 ratio. */
    private static final int IMAGE_WIDTH = 200;
    private static final int IMAGE_HEIGHT = 100;

    private PdfService service;
    private byte[] samplePdf;
    private byte[] samplePng;

    @BeforeEach
    void setUp() throws Exception {
        service = new PdfService(new LoadProperties(), new UploadValidator(new UploadProperties()));
        samplePdf = buildPdf(4);
        samplePng = buildPng();
    }

    // ── Geometry ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CONTAIN keeps the artwork's proportions and centres it in the requested box")
    void containPreservesRatio() {
        // A 2:1 image into a 306x396 box: width is the binding constraint, so it fills the width
        // and takes half the height, centred vertically in what was asked for.
        Placement placement = Placement.of(0.1f, 0.1f, 0.5f, 0.5f, 0f, ImageFit.CONTAIN);
        Rectangle2D.Float rect = placement.resolve(612f, 792f, IMAGE_WIDTH, IMAGE_HEIGHT);

        assertEquals(306f, rect.width, 0.01f);
        assertEquals(153f, rect.height, 0.01f);
        assertEquals(2.0f, rect.width / rect.height, 0.001f, "the image's own ratio must survive");
        assertEquals(61.2f, rect.x, 0.01f);
        // Box bottom is 792 - 79.2 - 396 = 316.8; centring 153 inside 396 adds 121.5.
        assertEquals(438.3f, rect.y, 0.01f);
    }

    @Test
    @DisplayName("STRETCH still fills the requested box exactly, for callers that mean it")
    void stretchFillsTheBox() {
        Placement placement = Placement.of(0.1f, 0.1f, 0.5f, 0.5f, 0f, ImageFit.STRETCH);
        Rectangle2D.Float rect = placement.resolve(612f, 792f, IMAGE_WIDTH, IMAGE_HEIGHT);

        assertEquals(306f, rect.width, 0.01f);
        assertEquals(396f, rect.height, 0.01f);
    }

    @Test
    @DisplayName("a placement without a complete box is no placement at all")
    void incompleteBoxMeansNaturalSize() {
        assertNull(Placement.of(null, 0.1f, 0.5f, 0.5f, 0f, ImageFit.CONTAIN));
        assertNull(Placement.of(0.1f, 0.1f, 0f, 0.5f, 0f, ImageFit.CONTAIN),
                "a zero-width box cannot be drawn into");
    }

    // ── Placement, end to end ─────────────────────────────────────────────────────

    @Test
    @DisplayName("place-image draws at the image's own ratio by default, not the box's")
    void placedImageIsNotDistorted() throws Exception {
        byte[] result = bytesOf(service.placeImage(null, List.of(1),
                Placement.of(0.1f, 0.1f, 0.5f, 0.5f, 0f, ImageFit.CONTAIN), pdfUpload(), imageUpload()));

        List<Rectangle2D.Float> drawn = DrawnImages.on(result, 1);
        assertEquals(1, drawn.size());
        assertEquals(2.0f, drawn.get(0).width / drawn.get(0).height, 0.01f);
        assertEquals(306f, drawn.get(0).width, 0.5f);
        assertEquals(153f, drawn.get(0).height, 0.5f);
    }

    @Test
    @DisplayName("place-image honours STRETCH when the caller explicitly asks for it")
    void stretchIsStillAvailable() throws Exception {
        byte[] result = bytesOf(service.placeImage(null, List.of(0),
                Placement.of(0.1f, 0.1f, 0.5f, 0.5f, 0f, ImageFit.STRETCH), pdfUpload(), imageUpload()));

        Rectangle2D.Float rect = DrawnImages.on(result, 0).get(0);
        assertEquals(306f, rect.width, 0.5f);
        assertEquals(396f, rect.height, 0.5f);
    }

    @Test
    @DisplayName("place-image covers every requested page and embeds the image only once")
    void placedImageSpansPagesWithoutDuplicatingTheImage() throws Exception {
        byte[] result = bytesOf(service.placeImage(null, List.of(0, 2, 3),
                Placement.of(0.1f, 0.1f, 0.4f, 0.4f, 0f, ImageFit.CONTAIN), pdfUpload(), imageUpload()));

        assertEquals(1, DrawnImages.on(result, 0).size());
        assertEquals(0, DrawnImages.on(result, 1).size(), "page 1 was not selected");
        assertEquals(1, DrawnImages.on(result, 2).size());
        assertEquals(1, DrawnImages.on(result, 3).size());

        // The result must not grow in proportion to the page count: one XObject, three references.
        assertEquals(1, distinctImageXObjects(result),
                "the image should be embedded once and referenced by each page");
    }

    @Test
    @DisplayName("place-image rejects a page selection that lands nowhere, rather than silently doing nothing")
    void placeImageRejectsOutOfRangePages() {
        assertThrows(Exception.class, () -> service.placeImage(null, List.of(99),
                Placement.of(0.1f, 0.1f, 0.4f, 0.4f, 0f, ImageFit.CONTAIN), pdfUpload(), imageUpload()));
    }

    // ── Stamping ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an image can be used as a stamp, over a page range")
    void imageStamp() throws Exception {
        byte[] result = bytesOf(service.stampPdf(null, 0.5f, 1, 2,
                Placement.of(0.25f, 0.25f, 0.5f, 0.5f, 0f, ImageFit.CONTAIN),
                pdfUpload(), stampUpload(samplePng, "logo.png")));

        assertEquals(0, DrawnImages.on(result, 0).size());
        assertEquals(1, DrawnImages.on(result, 1).size());
        assertEquals(1, DrawnImages.on(result, 2).size());
        assertEquals(0, DrawnImages.on(result, 3).size());
        assertEquals(2.0f, DrawnImages.on(result, 1).get(0).width
                / DrawnImages.on(result, 1).get(0).height, 0.01f);
    }

    @Test
    @DisplayName("a PDF stamp with no box behaves exactly as it always has")
    void pdfStampWithoutPlacementStillWorks() throws Exception {
        byte[] stampPdf = buildPdf(1);
        byte[] result = bytesOf(service.stampPdf(null, 1.0f, null, null, null,
                pdfUpload(), stampUpload(stampPdf, "stamp.pdf")));

        try (PDDocument doc = Loader.loadPDF(result)) {
            assertEquals(4, doc.getNumberOfPages());
        }
    }

    @Test
    @DisplayName("a PDF stamp can now be positioned too")
    void pdfStampWithPlacement() throws Exception {
        byte[] stampPdf = buildPdf(1);
        byte[] result = bytesOf(service.stampPdf(null, 1.0f, 0, 0,
                Placement.of(0.1f, 0.1f, 0.3f, 0.3f, 0f, ImageFit.CONTAIN),
                pdfUpload(), stampUpload(stampPdf, "stamp.pdf")));

        try (PDDocument doc = Loader.loadPDF(result)) {
            assertEquals(4, doc.getNumberOfPages());
        }
    }

    @Test
    @DisplayName("a stamp that is neither a PDF nor an image is rejected before any work happens")
    void unusableStampIsRejected() {
        MultipartFile junk = new MockMultipartFile("stamp", "stamp.pdf", "application/pdf",
                "not a document".getBytes());
        assertThrows(Exception.class, () ->
                service.stampPdf(null, 1.0f, null, null, null, pdfUpload(), junk));
    }

    // ── Image to PDF ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a large photo becomes a printable A4 page, not a 55-inch one")
    void imageToPdfProducesARealPageSize() throws Exception {
        // 4000x3000 is an ordinary phone photo. As points that is a page nearly five feet wide.
        MultipartFile photo = new MockMultipartFile("files", "photo.png", "image/png", buildPng(4000, 3000));

        byte[] result = bytesOf(service.imageToPdf(null, ImagePageSize.A4, PageOrientation.AUTO, 0f,
                List.of(photo)));

        try (PDDocument doc = Loader.loadPDF(result)) {
            var box = doc.getPage(0).getMediaBox();
            // Landscape photo, AUTO orientation, so A4 turned on its side.
            assertEquals(842f, box.getWidth(), 0.5f);
            assertEquals(595f, box.getHeight(), 0.5f);
        }

        Rectangle2D.Float drawn = DrawnImages.on(result, 0).get(0);
        assertEquals(4f / 3f, drawn.width / drawn.height, 0.01f, "the photo must not be distorted");
        // A4 is more elongated than 4:3, so the page's height is what constrains the photo.
        assertEquals(595f, drawn.height, 0.5f, "it should fill the height it has");
        assertEquals(793.33f, drawn.width, 0.5f);
    }

    @Test
    @DisplayName("mixed images all land on the same page size instead of one size each")
    void imageToPdfNormalisesPageSize() throws Exception {
        MultipartFile wide = new MockMultipartFile("files", "wide.png", "image/png", buildPng(1200, 400));
        MultipartFile tall = new MockMultipartFile("files", "tall.png", "image/png", buildPng(400, 1200));

        byte[] result = bytesOf(service.imageToPdf(null, ImagePageSize.A4, PageOrientation.PORTRAIT, 0f,
                List.of(wide, tall)));

        try (PDDocument doc = Loader.loadPDF(result)) {
            assertEquals(2, doc.getNumberOfPages());
            for (PDPage page : doc.getPages()) {
                assertEquals(595f, page.getMediaBox().getWidth(), 0.5f);
                assertEquals(842f, page.getMediaBox().getHeight(), 0.5f);
            }
        }
    }

    @Test
    @DisplayName("MATCH_IMAGE still gives one point per pixel, for callers that relied on it")
    void imageToPdfCanStillMatchTheImage() throws Exception {
        MultipartFile photo = new MockMultipartFile("files", "photo.png", "image/png", buildPng(640, 480));

        byte[] result = bytesOf(service.imageToPdf(null, ImagePageSize.MATCH_IMAGE, PageOrientation.AUTO, 0f,
                List.of(photo)));

        try (PDDocument doc = Loader.loadPDF(result)) {
            assertEquals(640f, doc.getPage(0).getMediaBox().getWidth(), 0.5f);
            assertEquals(480f, doc.getPage(0).getMediaBox().getHeight(), 0.5f);
        }
    }

    @Test
    @DisplayName("a margin insets the image without cropping or stretching it")
    void imageToPdfHonoursAMargin() throws Exception {
        MultipartFile square = new MockMultipartFile("files", "square.png", "image/png", buildPng(500, 500));

        byte[] result = bytesOf(service.imageToPdf(null, ImagePageSize.A4, PageOrientation.PORTRAIT, 36f,
                List.of(square)));

        Rectangle2D.Float drawn = DrawnImages.on(result, 0).get(0);
        // A square in a 523x770 content box fits to the width and is centred.
        assertEquals(523f, drawn.width, 0.5f);
        assertEquals(1.0f, drawn.width / drawn.height, 0.01f);
        assertEquals(36f, drawn.x, 0.5f);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────

    private MultipartFile pdfUpload() {
        return new MockMultipartFile("file", "sample.pdf", "application/pdf", samplePdf);
    }

    private MultipartFile imageUpload() {
        return new MockMultipartFile("image", "logo.png", "image/png", samplePng);
    }

    private MultipartFile stampUpload(byte[] content, String name) {
        return new MockMultipartFile("stamp", name, "application/octet-stream", content);
    }

    private static byte[] bytesOf(ResponseEntity<Resource> response) throws IOException {
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        return response.getBody().getInputStream().readAllBytes();
    }

    private static byte[] buildPdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(); // 612 x 792
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Page " + (i + 1));
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildPng() throws IOException {
        return buildPng(IMAGE_WIDTH, IMAGE_HEIGHT);
    }

    private static byte[] buildPng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** How many distinct image XObjects the whole document holds. */
    private static int distinctImageXObjects(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<Object> seen = new ArrayList<>();
            for (PDPage page : doc.getPages()) {
                if (page.getResources() == null) continue;
                for (COSName name : page.getResources().getXObjectNames()) {
                    Object cos = page.getResources().getCOSObject().getDictionaryObject(COSName.XOBJECT);
                    Object entry = ((org.apache.pdfbox.cos.COSDictionary) cos).getItem(name);
                    if (!seen.contains(entry)) seen.add(entry);
                }
            }
            return seen.size();
        }
    }

    /**
     * Reads back the rectangle each image was actually drawn into.
     *
     * <p>The drawn size is not recorded anywhere in the document; it exists only as the
     * transformation in force when the image operator runs. Replaying the page's content stream is
     * therefore the only way to assert that an image came out undistorted — checking the embedded
     * image's own pixel dimensions would pass even when the page draws it as a square.
     */
    private static final class DrawnImages extends PDFGraphicsStreamEngine {

        private final List<Rectangle2D.Float> boxes = new ArrayList<>();

        private DrawnImages(PDPage page) {
            super(page);
        }

        static List<Rectangle2D.Float> on(byte[] pdf, int pageIndex) throws IOException {
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                PDPage page = doc.getPage(pageIndex);
                DrawnImages engine = new DrawnImages(page);
                engine.processPage(page);
                return engine.boxes;
            }
        }

        @Override
        public void drawImage(PDImage pdImage) {
            // An image is drawn into the unit square, so the CTM's scale factors are its size on
            // the page and its translation is the bottom-left corner.
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            boxes.add(new Rectangle2D.Float(ctm.getTranslateX(), ctm.getTranslateY(),
                    ctm.getScalingFactorX(), ctm.getScalingFactorY()));
        }

        // Nothing else about the page is of interest here.
        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {}
        @Override public void clip(int windingRule) {}
        @Override public void moveTo(float x, float y) {}
        @Override public void lineTo(float x, float y) {}
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}
        @Override public Point2D getCurrentPoint() { return new Point2D.Float(0, 0); }
        @Override public void closePath() {}
        @Override public void endPath() {}
        @Override public void strokePath() {}
        @Override public void fillPath(int windingRule) {}
        @Override public void fillAndStrokePath(int windingRule) {}
        @Override public void shadingFill(COSName shadingName) {}
    }
}

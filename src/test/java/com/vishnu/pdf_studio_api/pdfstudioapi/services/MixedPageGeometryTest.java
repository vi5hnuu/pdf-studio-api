package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.LoadProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.UploadProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.RedactPdfRequest.RedactRegion;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.ImageFit;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.Placement;
import com.vishnu.pdf_studio_api.pdfstudioapi.validation.UploadValidator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the tools that place a box on every page of a document.
 *
 * <p>Crop and redact both took their box in <b>points</b>. A client can only convert what the
 * user drew into points against one page's dimensions — it has one preview — so on a document
 * whose pages are not all the same size, the box was right on that page and wrong on the rest.
 * Crop showed it worst: where the margins exceeded a smaller page the crop was skipped in
 * silence, so the file came back cropped on page one and untouched after it. Redact showed it
 * worst in consequence: a bar landing in the wrong place leaves the content it was meant to
 * remove sitting in the file.
 *
 * <p>Both now take the box as a fraction of the page it applies to, which cannot be wrong on any
 * page. These tests use documents with pages of different sizes and rotations, because a
 * document where every page matches cannot tell the two contracts apart.
 */
class MixedPageGeometryTest {

    private PdfService service;

    @BeforeEach
    void setUp() {
        service = new PdfService(new LoadProperties(), new UploadValidator(new UploadProperties()));
    }

    // ── Crop ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a fractional crop applies to every page, whatever size each one is")
    void fractionalCropIsCorrectOnEveryPage() throws Exception {
        // Letter then A5 — an ordinary report with a scanned insert.
        MultipartFile upload = upload(mixedSizes());

        // Keep the middle half of each page.
        Placement keep = Placement.of(0.25f, 0.25f, 0.5f, 0.5f, 0f, ImageFit.STRETCH);
        byte[] result = bytesOf(service.cropPdf(null, null, null, null, null, keep, List.of(), upload));

        try (PDDocument doc = Loader.loadPDF(result)) {
            PDRectangle first = doc.getPage(0).getCropBox();
            PDRectangle second = doc.getPage(1).getCropBox();

            assertEquals(306f, first.getWidth(), 0.5f);   // half of Letter's 612
            assertEquals(396f, first.getHeight(), 0.5f);  // half of Letter's 792
            assertEquals(210f, second.getWidth(), 1f);    // half of A5's 420
            assertEquals(297f, second.getHeight(), 1f);   // half of A5's 595
        }
    }

    @Test
    @DisplayName("point margins that a smaller page cannot meet are reported, not silently skipped")
    void impossiblePointMarginsAreReported() {
        MultipartFile upload = upload(mixedSizesBytes());

        // 214pt off each side is wider than the whole of page 2. This used to return a file in
        // which page 1 was cropped and page 2 was untouched, with no indication anything was wrong.
        Exception failure = assertThrows(Exception.class, () ->
                service.cropPdf(null, 264f, 264f, 214f, 214f, null, List.of(), upload));

        assertTrue(rootMessage(failure).contains("page 2"),
                "the error should name the page that could not be cropped: " + rootMessage(failure));
    }

    @Test
    @DisplayName("a crop drawn on a rotated page lands where the user drew it")
    void cropFollowsPageRotation() throws Exception {
        // The viewer shows a /Rotate 90 page on its side, so the top-left the user drew over is
        // not the top-left of the page as stored.
        MultipartFile upload = upload(rotatedPage(90));

        // Keep the left half of what is displayed.
        Placement keep = Placement.of(0f, 0f, 0.5f, 1f, 0f, ImageFit.STRETCH);
        byte[] result = bytesOf(service.cropPdf(null, null, null, null, null, keep, List.of(), upload));

        try (PDDocument doc = Loader.loadPDF(result)) {
            PDRectangle crop = doc.getPage(0).getCropBox();
            // Displayed width is the page's stored height, so half of it is half the stored
            // height — the crop must span the full stored width and half the stored height.
            assertEquals(612f, crop.getWidth(), 0.5f);
            assertEquals(396f, crop.getHeight(), 0.5f);
            // Turned 90° clockwise, the stored bottom edge is what runs down the left of the
            // display, so the kept half is the bottom of the page as stored.
            assertEquals(0f, crop.getLowerLeftY(), 0.5f);
        }
    }

    @Test
    @DisplayName("point margins still work for callers that have not been updated")
    void pointMarginsStillHonoured() throws Exception {
        MultipartFile upload = upload(uniformPages());
        byte[] result = bytesOf(service.cropPdf(null, 20f, 20f, 20f, 20f, null, List.of(), upload));

        try (PDDocument doc = Loader.loadPDF(result)) {
            for (PDPage page : doc.getPages()) {
                assertEquals(572f, page.getCropBox().getWidth(), 0.5f);
            }
        }
    }

    // ── Redact ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a fractional redaction covers the same part of every page it is drawn on")
    void fractionalRedactionIsCorrectOnEveryPage() throws Exception {
        byte[] result = bytesOf(service.redactPdf(null,
                List.of(fractionalRegion(0, 0.1f, 0.1f, 0.5f, 0.2f),
                        fractionalRegion(1, 0.1f, 0.1f, 0.5f, 0.2f)),
                upload(mixedSizes())));

        try (PDDocument doc = Loader.loadPDF(result)) {
            assertEquals(2, doc.getNumberOfPages());
        }

        // Both bars must be a proportion of their own page: the same fractions on a Letter page
        // and an A5 page are different numbers of points.
        assertEquals(306f, resolvedOn(mixedSizes(), 0, 0.1f, 0.1f, 0.5f, 0.2f).width, 0.5f);
        assertEquals(210f, resolvedOn(mixedSizes(), 1, 0.1f, 0.1f, 0.5f, 0.2f).width, 1f);
    }

    @Test
    @DisplayName("point coordinates still work for callers that have not been updated")
    void redactionPointCoordinatesStillHonoured() throws Exception {
        RedactRegion region = new RedactRegion();
        region.setPage(0);
        region.setX(50);
        region.setY(50);
        region.setWidth(100);
        region.setHeight(20);

        byte[] result = bytesOf(service.redactPdf(null, List.of(region), upload(uniformPages())));
        try (PDDocument doc = Loader.loadPDF(result)) {
            assertEquals(2, doc.getNumberOfPages());
        }
    }

    // ── Placement ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an image placed on a rotated page lands where it was drawn, the right way up")
    void placementFollowsPageRotation() throws Exception {
        // A 2:1 image into the top-left quarter of a page displayed on its side.
        MultipartFile pdf = upload(rotatedPage(90));
        MultipartFile image = new MockMultipartFile("image", "logo.png", "image/png", wideImage());

        byte[] result = bytesOf(service.placeImage(null, List.of(0),
                Placement.of(0f, 0f, 0.5f, 0.5f, 0f, ImageFit.CONTAIN), pdf, image));

        Rectangle2D.Float drawn = ArtworkPlacementTest.drawnImagesOn(result, 0).get(0);

        // Displayed, the page is 792 wide by 612 tall, so the box is 396 x 306 and a 2:1 image
        // fits it at 396 x 198. On the page as stored those dimensions are the other way round.
        assertEquals(198f, drawn.width, 1f);
        assertEquals(396f, drawn.height, 1f);

        // Turned 90° clockwise, the display's top-left quarter is the stored page's bottom-left.
        // The image is centred in the half-height box, which puts it 54pt in from the stored left.
        assertEquals(54f, drawn.x, 1f);
        assertEquals(0f, drawn.y, 1f);
    }

    private static byte[] wideImage() throws IOException {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(200, 100, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.RED);
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ── Geometry ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every quarter turn maps a displayed box back into the page's own coordinates")
    void rotationMapping() {
        // The left half of what is displayed, in each orientation.
        Placement leftHalf = Placement.of(0f, 0f, 0.5f, 1f, 0f, ImageFit.STRETCH);

        assertEquals(new Rectangle2D.Float(0, 0, 306, 792),
                leftHalf.forPageRotation(0).resolve(612, 792, 0, 0));
        // Turned 90° clockwise, the page's bottom edge is what runs down the left of the
        // display, so the displayed left half is the stored bottom half.
        assertEquals(new Rectangle2D.Float(0, 0, 612, 396),
                leftHalf.forPageRotation(90).resolve(612, 792, 0, 0));
        assertEquals(new Rectangle2D.Float(306, 0, 306, 792),
                leftHalf.forPageRotation(180).resolve(612, 792, 0, 0));
        // Turned the other way, it is the stored top half.
        assertEquals(new Rectangle2D.Float(0, 396, 612, 396),
                leftHalf.forPageRotation(270).resolve(612, 792, 0, 0));
    }

    @Test
    @DisplayName("a rotation that is not a quarter turn is ignored rather than skewing the box")
    void oddRotationIsIgnored() {
        Placement box = Placement.of(0.1f, 0.2f, 0.3f, 0.4f, 0f, ImageFit.STRETCH);
        assertSame(box, box.forPageRotation(45));
        assertSame(box, box.forPageRotation(0));
        // Negative and over-turned values are normalised rather than rejected.
        assertEquals(box.forPageRotation(90), box.forPageRotation(450));
        assertEquals(box.forPageRotation(270), box.forPageRotation(-90));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────

    private static Rectangle2D.Float resolvedOn(byte[] pdf, int pageIndex,
                                                float x, float y, float w, float h) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDRectangle mediaBox = doc.getPage(pageIndex).getMediaBox();
            return Placement.of(x, y, w, h, 0f, ImageFit.STRETCH)
                    .resolve(mediaBox.getWidth(), mediaBox.getHeight(), 0, 0);
        }
    }

    private static RedactRegion fractionalRegion(int page, float x, float y, float w, float h) {
        RedactRegion region = new RedactRegion();
        region.setPage(page);
        region.setXFrac(x);
        region.setYFrac(y);
        region.setWidthFrac(w);
        region.setHeightFrac(h);
        return region;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        StringBuilder all = new StringBuilder();
        while (current != null) {
            if (current.getMessage() != null) all.append(current.getMessage()).append(' ');
            current = current.getCause();
        }
        return all.toString();
    }

    private static MultipartFile upload(byte[] pdf) {
        return new MockMultipartFile("file", "doc.pdf", "application/pdf", pdf);
    }

    private static byte[] bytesOf(ResponseEntity<Resource> response) throws IOException {
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        return response.getBody().getInputStream().readAllBytes();
    }

    private static byte[] mixedSizesBytes() {
        try {
            return mixedSizes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Letter followed by A5 — pages of different sizes in one document. */
    private static byte[] mixedSizes() throws IOException {
        return build(document -> {
            document.addPage(new PDPage(PDRectangle.LETTER));
            document.addPage(new PDPage(PDRectangle.A5));
        });
    }

    private static byte[] uniformPages() throws IOException {
        return build(document -> {
            document.addPage(new PDPage(PDRectangle.LETTER));
            document.addPage(new PDPage(PDRectangle.LETTER));
        });
    }

    private static byte[] rotatedPage(int degrees) throws IOException {
        return build(document -> {
            PDPage page = new PDPage(PDRectangle.LETTER);
            page.setRotation(degrees);
            document.addPage(page);
        });
    }

    private interface Pages {
        void addTo(PDDocument document);
    }

    private static byte[] build(Pages pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            pages.addTo(document);
            document.save(out);
            return out.toByteArray();
        }
    }
}

package com.vishnu.pdf_studio_api.pdfstudioapi.utils;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@code PdfTools.renderedSize} to what the renderer actually produces.
 *
 * <p>pdf-to-jpg's single-image mode allocates one canvas before rendering any page, so the
 * predicted size has to equal the real one. {@code Graphics2D.drawImage} clips silently rather
 * than throwing, so a mismatch does not fail — it quietly returns a document with content cut
 * off, which is the hardest kind of bug to notice in an image of a page.
 *
 * <p>Rotation and a crop box smaller than the media box are the two cases that actually broke:
 * measuring the media box without swapping for {@code /Rotate} halved every rotated page.
 */
class PdfRenderedSizeTest {

    private void assertPredictionMatchesRenderer(PDPage page, int dpi) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(page);
            Dimension predicted = PdfTools.renderedSize(doc.getPage(0), dpi);
            BufferedImage actual = new PDFRenderer(doc).renderImageWithDPI(0, dpi, ImageType.RGB);
            assertEquals(actual.getWidth(), predicted.width,
                    "width predicted for rotation " + page.getRotation() + " at " + dpi + " DPI");
            assertEquals(actual.getHeight(), predicted.height,
                    "height predicted for rotation " + page.getRotation() + " at " + dpi + " DPI");
        }
    }

    @Test
    @DisplayName("an upright A4 page is predicted exactly, at every quality")
    void uprightPage() throws Exception {
        for (int dpi : new int[]{72, 150, 300}) {
            assertPredictionMatchesRenderer(new PDPage(PDRectangle.A4), dpi);
        }
    }

    @Test
    @DisplayName("a rotated page is predicted with its sides swapped")
    void rotatedPage() throws Exception {
        for (int rotation : new int[]{90, 180, 270}) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setRotation(rotation);
            assertPredictionMatchesRenderer(page, 150);
        }
    }

    @Test
    @DisplayName("a page cropped smaller than its media box is predicted from the crop box")
    void croppedPage() throws Exception {
        PDPage page = new PDPage(PDRectangle.A4);
        page.setCropBox(new PDRectangle(20, 30, 300, 500));
        assertPredictionMatchesRenderer(page, 150);
    }

    @Test
    @DisplayName("a page whose size floors differently from rounding is still exact")
    void nonIntegerScale() throws Exception {
        // 100.7pt at 150 DPI is 209.79px: floor gives 209, rounding would give 210, and one
        // pixel of mismatch per page is enough to clip the last page of a long strip.
        PDPage page = new PDPage(new PDRectangle(100.7f, 200.3f));
        assertPredictionMatchesRenderer(page, 150);
    }
}

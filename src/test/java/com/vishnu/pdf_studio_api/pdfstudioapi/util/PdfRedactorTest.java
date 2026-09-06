package com.vishnu.pdf_studio_api.pdfstudioapi.util;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.RedactPdfRequest.RedactRegion;
import com.vishnu.pdf_studio_api.pdfstudioapi.utils.PdfTools;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redaction has to remove content, not cover it.
 *
 * <p>The tool used to append a filled black rectangle and nothing else, so every "redacted"
 * account number came straight back out of the file with a text extractor. These tests fail
 * if that ever returns.
 */
class PdfRedactorTest {

    private static final String SECRET = "Account 4111111111111111";
    private static final String KEEP = "This line must survive";

    @Test
    void removesTheTextUnderTheRegion() throws Exception {
        Path pdf = write(page -> {
        });

        byte[] redacted = PdfTools.redactPdf(pdf, List.of(region(0, 40, 80, 400, 20)));

        assertThat(textOf(redacted))
                .as("the covered text must not be recoverable from the file")
                .doesNotContain(SECRET);
    }

    @Test
    void leavesTextOutsideTheRegionAlone() throws Exception {
        Path pdf = write(page -> {
        });

        byte[] redacted = PdfTools.redactPdf(pdf, List.of(region(0, 40, 80, 400, 20)));

        assertThat(textOf(redacted))
                .as("only what the user covered should go")
                .contains(KEEP);
    }

    @Test
    void aRegionCoveringNothingChangesNoText() throws Exception {
        Path pdf = write(page -> {
        });

        byte[] redacted = PdfTools.redactPdf(pdf, List.of(region(0, 10, 700, 50, 20)));

        String text = textOf(redacted);
        assertThat(text).contains(SECRET);
        assertThat(text).contains(KEEP);
    }


    @Test
    void removesOnlyTheCoveredWordAndLeavesTheRestInPlace() throws Exception {
        Path pdf = write(page -> {
        });

        // "Account " is roughly 55pt wide at 14pt Helvetica; cover the number that follows it.
        byte[] redacted = PdfTools.redactPdf(pdf, List.of(region(0, 105, 80, 200, 20)));

        String text = textOf(redacted);
        assertThat(text).as("the word before the region stays").contains("Account");
        assertThat(text).as("the covered digits go").doesNotContain("4111111111111111");
    }

    @Test
    void doesNotShiftTheTextThatSurvivesOnTheSameLine() throws Exception {
        Path pdf = write(page -> {
        });
        float before = xOfLastGlyphOnFirstLine(Files.readAllBytes(pdf));

        // Cover a slice in the middle of the first line.
        byte[] redacted = PdfTools.redactPdf(pdf, List.of(region(0, 120, 80, 60, 20)));

        assertThat(xOfLastGlyphOnFirstLine(redacted))
                .as("removing a glyph must not slide the rest of the line left")
                .isCloseTo(before, org.assertj.core.data.Offset.offset(0.5f));
    }

    /** X of the right-most glyph on the top line, used to detect layout drift. */
    private static float xOfLastGlyphOnFirstLine(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            float[] rightMost = {0};
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, java.util.List<org.apache.pdfbox.text.TextPosition> positions) {
                    for (var position : positions) {
                        // The first line sits near the top of the page.
                        if (position.getYDirAdj() < 120) {
                            rightMost[0] = Math.max(rightMost[0], position.getXDirAdj() + position.getWidthDirAdj());
                        }
                    }
                }
            };
            stripper.getText(doc);
            return rightMost[0];
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RedactRegion region(int page, float x, float y, float width, float height) {
        RedactRegion region = new RedactRegion();
        region.setPage(page);
        region.setX(x);
        region.setY(y);
        region.setWidth(width);
        region.setHeight(height);
        return region;
    }

    /** A one-page PDF with a line to redact at y=700 and a line to keep at y=650. */
    private static Path write(java.util.function.Consumer<PDPage> customise) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            customise.accept(page);

            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(50, 700);
                cs.showText(SECRET);
                cs.endText();

                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(50, 650);
                cs.showText(KEEP);
                cs.endText();
            }

            Path path = Files.createTempFile("redact-test", ".pdf");
            doc.save(path.toFile());
            path.toFile().deleteOnExit();
            return path;
        }
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}

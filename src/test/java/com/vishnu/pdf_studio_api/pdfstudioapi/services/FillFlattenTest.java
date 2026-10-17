package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.CreateFormRequest.FormFieldSpec;
import com.vishnu.pdf_studio_api.pdfstudioapi.exception.ApiException;
import com.vishnu.pdf_studio_api.pdfstudioapi.utils.PdfTools;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what fill-flatten does with a value a field will not take.
 *
 * <p>Swallowing it was wrong — flatten() then baked in the empty field and returned 200, losing
 * the user's data irreversibly with nothing to indicate it. But failing on *every* rejection was
 * also wrong: a signature field throws unconditionally from setValue(String), and clients send
 * one because get-form-fields lists it, so that would have failed every contract and agreement.
 */
class FillFlattenTest {

    private static FormFieldSpec spec(String type, String name, float y) {
        FormFieldSpec f = new FormFieldSpec();
        f.setType(type);
        f.setName(name);
        f.setPage(0);
        f.setX(72);
        f.setY(y);
        f.setWidth(180);
        f.setHeight(24);
        return f;
    }

    /** A one-page PDF carrying a text field and a signature field. */
    private Path formWithSignature() throws IOException {
        Path blank = Files.createTempFile("fill-flatten-test-", ".pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            doc.save(blank.toFile());
        }
        byte[] withForm = PdfTools.createForm(blank,
                List.of(spec("text", "fullName", 600), spec("signature", "signature", 500)));
        Path path = Files.createTempFile("fill-flatten-form-", ".pdf");
        Files.write(path, withForm);
        return path;
    }

    @Test
    @DisplayName("a blank for a signature field is skipped, not treated as a failure")
    void signatureFieldIsSkipped() throws Exception {
        final Path form = formWithSignature();
        // Exactly what the app sends: every discovered field, signature included.
        final byte[] out = assertDoesNotThrow(() -> PdfTools.fillFlatten(form,
                Map.of("fullName", "Ada Lovelace", "signature", "")));
        assertTrue(out.length > 0);
        // The text field's value still has to survive into the flattened page.
        try (PDDocument doc = Loader.loadPDF(out)) {
            assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(doc).contains("Ada"),
                    "the filled value should be baked into the page");
        }
    }

    @Test
    @DisplayName("a value a fillable field rejects is reported, naming the field")
    void rejectedValueIsReported() throws Exception {
        final Path form = formWithSignature();
        // A checkbox only accepts its own export value or Off, so a word it does not know is a
        // genuine rejection rather than a structural one.
        Path blank = Files.createTempFile("fill-flatten-cb-", ".pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            doc.save(blank.toFile());
        }
        byte[] withCheckbox = PdfTools.createForm(blank, List.of(spec("checkbox", "agree", 600)));
        Path cbForm = Files.createTempFile("fill-flatten-cb-form-", ".pdf");
        Files.write(cbForm, withCheckbox);

        ApiException thrown = assertThrows(ApiException.class,
                () -> PdfTools.fillFlatten(cbForm, Map.of("agree", "definitely-not-an-option")));
        assertTrue(thrown.getMessage().contains("agree"),
                "the failure should name the field, was: " + thrown.getMessage());
        assertTrue(form.toFile().exists());
    }
}

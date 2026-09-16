package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.CreateFormRequest.FormFieldSpec;
import com.vishnu.pdf_studio_api.pdfstudioapi.utils.PdfTools;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers turning a page into a real AcroForm.
 *
 * <p>The regression here: checkbox and radio appearances were drawn by showing "4" and "l" in
 * ZapfDingbats. PDFBox maps those *Unicode* characters through ZapfDingbatsEncoding, which has no
 * glyph for either, so it threw "U+0034 ('.notdef') is not available in the font ZapfDingbats"
 * and failed the whole request — a form containing a single checkbox could not be created at all.
 */
class CreateFormTest {

    private Path onePagePdf() throws IOException {
        Path path = Files.createTempFile("create-form-test-", ".pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.LETTER));
            doc.save(path.toFile());
        }
        return path;
    }

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

    @Test
    void buildsEveryFieldTypeTheEditorCanPlace() throws Exception {
        Path pdf = onePagePdf();
        try {
            FormFieldSpec dropdown = spec("dropdown", "choice", 200);
            dropdown.setOptions(List.of("One", "Two"));

            FormFieldSpec radio = spec("radio", "agree", 260);
            radio.setExportValue("yes");

            byte[] out = PdfTools.createForm(pdf, List.of(
                    spec("text", "full_name", 80),
                    spec("multiline", "notes", 110),
                    spec("checkbox", "accept", 140),
                    radio,
                    dropdown,
                    spec("date", "signed_on", 320),
                    spec("signature", "signature", 380)));

            assertNotNull(out);
            try (PDDocument doc = Loader.loadPDF(out)) {
                PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
                assertNotNull(form, "the output should carry an AcroForm");
                assertNotNull(form.getField("full_name"));
                assertInstanceOf(PDCheckBox.class, form.getField("accept"));
                assertInstanceOf(PDRadioButton.class, form.getField("agree"));
                assertInstanceOf(PDComboBox.class, form.getField("choice"));
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void aCheckboxAloneDoesNotFailTheRequest() throws Exception {
        Path pdf = onePagePdf();
        try {
            FormFieldSpec checkbox = spec("checkbox", "accept", 140);
            checkbox.setChecked(true);
            byte[] out = PdfTools.createForm(pdf, List.of(checkbox));
            try (PDDocument doc = Loader.loadPDF(out)) {
                PDCheckBox box = (PDCheckBox) doc.getDocumentCatalog().getAcroForm().getField("accept");
                assertTrue(box.isChecked(), "a checkbox sent as checked should come back checked");
                assertFalse(box.getWidgets().isEmpty(), "it needs a widget to be visible");
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void radioOptionsSharingAGroupBecomeOneField() throws Exception {
        Path pdf = onePagePdf();
        try {
            FormFieldSpec yes = spec("radio", "colour", 200);
            yes.setExportValue("red");
            FormFieldSpec no = spec("radio", "colour", 240);
            no.setExportValue("blue");

            byte[] out = PdfTools.createForm(pdf, List.of(yes, no));
            try (PDDocument doc = Loader.loadPDF(out)) {
                PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
                PDRadioButton group = (PDRadioButton) form.getField("colour");
                assertNotNull(group);
                assertEquals(2, group.getWidgets().size(), "both options belong to one radio field");
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }
}

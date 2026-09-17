package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.CreateFormRequest.FormFieldSpec;
import com.vishnu.pdf_studio_api.pdfstudioapi.utils.PdfTools;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.cos.COSName;
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
    void drawsEachRadioOptionsLabelOntoThePage() throws Exception {
        // Before this, a radio group in the output was three identical circles with nothing to
        // say which was "Savings" and which was "Current": the spec had no label at all, and
        // /TU (tooltip) is hover help that never reaches paper.
        Path pdf = onePagePdf();
        try {
            FormFieldSpec savings = spec("radio", "account_type", 200);
            savings.setExportValue("savings");
            savings.setWidth(12);
            savings.setHeight(12);
            savings.setLabel("Savings");

            FormFieldSpec current = spec("radio", "account_type", 230);
            current.setExportValue("current");
            current.setWidth(12);
            current.setHeight(12);
            current.setLabel("Current");

            byte[] out = PdfTools.createForm(pdf, List.of(savings, current));
            try (PDDocument doc = Loader.loadPDF(out)) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
                assertTrue(text.contains("Savings"), "the first option's label must be on the page");
                assertTrue(text.contains("Current"), "the second option's label must be on the page");

                // Still one radio field with both options, not two fields.
                PDAcroForm acro = doc.getDocumentCatalog().getAcroForm();
                PDField field = acro.getField("account_type");
                assertInstanceOf(PDRadioButton.class, field);
                assertEquals(2, field.getWidgets().size());
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void aFieldWithNoLabelDrawsNothingExtra() throws Exception {
        // The common case: the document already prints its own labels, so the tool must not
        // stamp anything onto it.
        Path pdf = onePagePdf();
        try {
            byte[] out = PdfTools.createForm(pdf, List.of(spec("text", "plain", 200)));
            try (PDDocument doc = Loader.loadPDF(out)) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
                assertTrue(text.isBlank(), "expected no page text, got: " + text);
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
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
    void appliesTheRichTextTraits() throws Exception {
        Path pdf = onePagePdf();
        try {
            FormFieldSpec f = spec("text", "reference", 200);
            f.setTooltip("Your 8-character booking reference");
            f.setReadOnly(true);
            f.setMaxLength(8);
            f.setComb(true);
            f.setAlignment(1); // centred

            byte[] out = PdfTools.createForm(pdf, List.of(f));
            try (PDDocument doc = Loader.loadPDF(out)) {
                PDTextField tf = (PDTextField) doc.getDocumentCatalog().getAcroForm().getField("reference");
                assertEquals("Your 8-character booking reference",
                        tf.getCOSObject().getString(COSName.TU), "tooltip is written as /TU");
                assertTrue(tf.isReadOnly());
                assertEquals(8, tf.getMaxLen());
                assertTrue(tf.isComb(), "comb is allowed here because a MaxLen is present");
                assertEquals(1, tf.getQ(), "centre maps to quadding 1");
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void refusesCombOnAMultilineFieldWhereThePdfSpecForbidsIt() throws Exception {
        Path pdf = onePagePdf();
        try {
            FormFieldSpec f = spec("multiline", "notes", 200);
            f.setMaxLength(40);
            f.setComb(true);

            byte[] out = PdfTools.createForm(pdf, List.of(f));
            try (PDDocument doc = Loader.loadPDF(out)) {
                PDTextField tf = (PDTextField) doc.getDocumentCatalog().getAcroForm().getField("notes");
                assertTrue(tf.isMultiline());
                assertFalse(tf.isComb(), "comb and multiline cannot both be set");
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void writesCalculationAndValidationScriptsForDesktopReaders() throws Exception {
        Path pdf = onePagePdf();
        try {
            FormFieldSpec total = spec("number", "total", 200);
            total.setFormat("number");
            FormFieldSpec.CalculationSpec calc = new FormFieldSpec.CalculationSpec();
            calc.setFunction("SUM");
            calc.setFields(List.of("a", "b"));
            total.setCalculation(calc);
            total.setValidationPattern("^[0-9]+$");

            byte[] out = PdfTools.createForm(pdf, List.of(total));
            try (PDDocument doc = Loader.loadPDF(out)) {
                PDTextField tf = (PDTextField) doc.getDocumentCatalog().getAcroForm().getField("total");
                var actions = tf.getActions();
                assertNotNull(actions, "the field should carry additional actions");
                assertNotNull(actions.getC(), "a calculation action");
                assertNotNull(actions.getV(), "a validation action");
                assertNotNull(actions.getF(), "a format action");
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void buildsAMultiSelectListBox() throws Exception {
        Path pdf = onePagePdf();
        try {
            FormFieldSpec list = spec("listbox", "languages", 200);
            list.setOptions(List.of("English", "Hindi", "Tamil"));
            list.setMultiSelect(true);

            byte[] out = PdfTools.createForm(pdf, List.of(list));
            try (PDDocument doc = Loader.loadPDF(out)) {
                PDListBox box = (PDListBox) doc.getDocumentCatalog().getAcroForm().getField("languages");
                assertNotNull(box);
                assertTrue(box.isMultiSelect());
                assertEquals(3, box.getOptions().size());
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void dataEntryFieldsAreVisibleWithoutAReaderSynthesisingThem() throws Exception {
        // Without a border and background an empty text field is invisible in readers that
        // ignore NeedAppearances — the person filling the form cannot see where to type.
        Path pdf = onePagePdf();
        try {
            byte[] out = PdfTools.createForm(pdf, List.of(spec("text", "who", 200)));
            try (PDDocument doc = Loader.loadPDF(out)) {
                var widget = doc.getDocumentCatalog().getAcroForm()
                        .getField("who").getWidgets().get(0);
                assertNotNull(widget.getBorderStyle(), "the field needs a border style");
                var mk = widget.getAppearanceCharacteristics();
                assertNotNull(mk, "the field needs appearance characteristics");
                assertNotNull(mk.getBorderColour(), "and a visible outline");
                assertNotNull(mk.getBackground(), "and a background tint");
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void writesADateFormatActionSoADateFieldIsNotJustATextBox() throws Exception {
        Path pdf = onePagePdf();
        try {
            FormFieldSpec date = spec("date", "signed_on", 200);
            date.setFormat("date");
            date.setDateFormat("dd/mm/yyyy");

            byte[] out = PdfTools.createForm(pdf, List.of(date));
            try (PDDocument doc = Loader.loadPDF(out)) {
                PDTextField tf = (PDTextField) doc.getDocumentCatalog().getAcroForm().getField("signed_on");
                var actions = tf.getActions();
                assertNotNull(actions, "a date field should carry format actions");
                assertNotNull(actions.getF(), "a format action");
                assertNotNull(actions.getK(), "a keystroke action");
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

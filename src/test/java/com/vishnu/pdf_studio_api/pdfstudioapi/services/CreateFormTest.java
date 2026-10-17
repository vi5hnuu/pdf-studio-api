package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.CreateFormRequest.FormFieldSpec;
import com.vishnu.pdf_studio_api.pdfstudioapi.exception.ApiException;
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
    void flatteningDoesNotStampTheFillTimeGreyBoxIntoThePage() throws Exception {
        // Data-entry widgets get a light grey background and outline so an empty field is
        // visible to whoever is filling the form. flatten() bakes each widget's appearance into
        // the page, so without stripping that first a finished document came out covered in
        // grey boxes.
        Path pdf = onePagePdf();
        try {
            FormFieldSpec text = spec("text", "full_name", 200);
            byte[] form = PdfTools.createForm(pdf, List.of(text));

            Path withForm = Files.createTempFile("flatten-test-", ".pdf");
            Files.write(withForm, form);
            try (PDDocument doc = Loader.loadPDF(withForm.toFile())) {
                // Sanity: the styling really is there before flattening.
                assertNotNull(doc.getDocumentCatalog().getAcroForm().getField("full_name"));

                byte[] flat = PdfTools.flattenPdf(doc);
                try (PDDocument out = Loader.loadPDF(flat)) {
                    String content = pageContent(out);
                    // `sc` / `SC`, not `rg` / `RG`: PDFBox's appearance generator sets the
                    // colour space with `/DeviceRGB cs` and then uses the generic operators.
                    // A first version of this test looked for `rg` and therefore passed with
                    // the fix removed — worth stating, because the wrong operator makes this
                    // assertion silently meaningless rather than failing.
                    assertFalse(content.contains("0.96 0.96 0.96 sc"),
                            "the fill-time background must not survive flattening:\n" + content);
                    assertFalse(content.contains("0.45 0.45 0.45 SC"),
                            "the fill-time border must not survive flattening:\n" + content);
                }
            } finally {
                Files.deleteIfExists(withForm);
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    /**
     * First page's drawing operators — its own content streams *and* the Form XObjects they
     * invoke.
     *
     * <p>The XObjects are the point. PDFBox's {@code flatten()} does not inline a widget's
     * appearance into the page stream; it imports it as a Form XObject and emits a {@code /Do}.
     * A first version of this helper read only the page's own streams, so it found nothing and
     * the test passed just as happily with the fix removed — which is how it was caught.
     */
    private static String pageContent(PDDocument doc) throws IOException {
        PDPage page = doc.getPage(0);
        StringBuilder sb = new StringBuilder();
        for (java.util.Iterator<org.apache.pdfbox.pdmodel.common.PDStream> it =
                     page.getContentStreams(); it.hasNext(); ) {
            sb.append(new String(it.next().toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1));
        }
        org.apache.pdfbox.pdmodel.PDResources res = page.getResources();
        if (res != null) {
            for (COSName name : res.getXObjectNames()) {
                org.apache.pdfbox.pdmodel.graphics.PDXObject xo = res.getXObject(name);
                if (xo instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) {
                    sb.append(new String(form.getCOSObject().toTextString()
                            .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                            java.nio.charset.StandardCharsets.ISO_8859_1));
                    sb.append(new String(form.getContentStream().toByteArray(),
                            java.nio.charset.StandardCharsets.ISO_8859_1));
                }
            }
        }
        return sb.toString();
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
    void labelPositionMovesTheCaptionToTheChosenSide() throws Exception {
        // Four presets rather than free coordinates: the caption is always attached to its
        // field, and the author picks the side that suits the form.
        for (String side : new String[]{"right", "left", "above", "below"}) {
            Path pdf = onePagePdf();
            try {
                FormFieldSpec box = spec("checkbox", "agree_" + side, 300);
                box.setWidth(18);
                box.setHeight(18);
                box.setLabel("Agree");
                box.setLabelPosition(side);

                byte[] out = PdfTools.createForm(pdf, List.of(box));
                try (PDDocument doc = Loader.loadPDF(out)) {
                    assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(doc).contains("Agree"),
                            "caption must be drawn for position " + side);
                }
            } finally {
                Files.deleteIfExists(pdf);
            }
        }
    }

    @Test
    void aCaptionNeverRunsOffThePage() throws Exception {
        // A left-positioned caption on a field at the very left edge would be drawn at a
        // negative X and simply vanish.
        Path pdf = onePagePdf();
        try {
            FormFieldSpec box = spec("checkbox", "edge", 300);
            box.setX(0);
            box.setWidth(18);
            box.setHeight(18);
            box.setLabel("A long caption pushed off the left edge");
            box.setLabelPosition("left");

            byte[] out = PdfTools.createForm(pdf, List.of(box));
            try (PDDocument doc = Loader.loadPDF(out)) {
                assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(doc).contains("caption"),
                        "the caption must be pulled back onto the page, not lost");
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

    // ── Properties the API accepted and then dropped ──────────────────────────────────

    @Test
    void optionsInAGroupKeepDistinctOnStatesEvenWhenTheirExportValuesSanitiseTheSame() throws Exception {
        // The sanitiser maps everything outside [A-Za-z0-9_] to '_', so a Hindi group's export
        // values all collapsed to "___": the kids shared one on-state and selecting one option
        // selected the whole group.
        Path pdf = onePagePdf();
        try {
            FormFieldSpec a = spec("radio", "gender", 200);
            a.setExportValue("पुरुष");
            FormFieldSpec b = spec("radio", "gender", 240);
            b.setExportValue("महिला");
            byte[] form = PdfTools.createForm(pdf, List.of(a, b));
            try (PDDocument doc = Loader.loadPDF(form)) {
                PDRadioButton radio = (PDRadioButton) doc.getDocumentCatalog()
                        .getAcroForm().getField("gender");
                List<String> exports = radio.getExportValues();
                assertEquals(2, exports.size());
                assertNotEquals(exports.get(0), exports.get(1),
                        "two options sharing an on-state toggle together");
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void aChoiceFieldGetsItsAlignmentAndFontSize() throws Exception {
        // PDChoice extends PDVariableText, so /Q and /DA both apply. The choice branches called
        // only applyCommonTraits, so an alignment or font size set on a dropdown was accepted
        // and silently dropped — which is why the editor hid both controls for choice fields.
        Path pdf = onePagePdf();
        try {
            FormFieldSpec drop = spec("dropdown", "country", 200);
            drop.setOptions(List.of("India", "United Kingdom"));
            drop.setAlignment(1);
            drop.setFontSize(11f);
            byte[] form = PdfTools.createForm(pdf, List.of(drop));
            try (PDDocument doc = Loader.loadPDF(form)) {
                PDComboBox combo = (PDComboBox) doc.getDocumentCatalog()
                        .getAcroForm().getField("country");
                assertEquals(1, combo.getQ());
                assertTrue(combo.getDefaultAppearance().contains("11"),
                        "font size should reach /DA, was: " + combo.getDefaultAppearance());
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void aChoiceFieldCanExportSomethingOtherThanWhatItDisplays() throws Exception {
        // Only the display list was ever set, so the value the PDF recorded was forced to be
        // the text shown — "United Kingdom" where the form wanted "GB".
        Path pdf = onePagePdf();
        try {
            FormFieldSpec drop = spec("dropdown", "country", 200);
            drop.setOptions(List.of("India", "United Kingdom"));
            drop.setOptionValues(List.of("IN", "GB"));
            byte[] form = PdfTools.createForm(pdf, List.of(drop));
            try (PDDocument doc = Loader.loadPDF(form)) {
                PDComboBox combo = (PDComboBox) doc.getDocumentCatalog()
                        .getAcroForm().getField("country");
                assertEquals(List.of("IN", "GB"), combo.getOptionsExportValues());
                assertEquals(List.of("India", "United Kingdom"), combo.getOptionsDisplayValues());
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void aDefaultValueIsWrittenToDvSoResetFormRestoresIt() throws Exception {
        // /DV was written for checkboxes only, so a reader's "Reset form" blanked every text
        // and choice field instead of returning it to the author's default.
        Path pdf = onePagePdf();
        try {
            FormFieldSpec text = spec("text", "country", 200);
            text.setValue("India");
            byte[] form = PdfTools.createForm(pdf, List.of(text));
            try (PDDocument doc = Loader.loadPDF(form)) {
                PDTextField field = (PDTextField) doc.getDocumentCatalog()
                        .getAcroForm().getField("country");
                assertEquals("India", field.getDefaultValue());
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void anEmailFieldActuallyValidates() throws Exception {
        // applyFieldActions handled only date and number, so format=email produced a plain text
        // box while the inspector showed a badge saying it validated.
        Path pdf = onePagePdf();
        try {
            FormFieldSpec email = spec("email", "contact", 200);
            email.setFormat("email");
            byte[] form = PdfTools.createForm(pdf, List.of(email));
            try (PDDocument doc = Loader.loadPDF(form)) {
                PDTextField field = (PDTextField) doc.getDocumentCatalog()
                        .getAcroForm().getField("contact");
                assertNotNull(field.getActions(), "no additional actions were written");
                assertNotNull(field.getActions().getV(), "no validate action was written");
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void anUnknownTypeIsRejectedRatherThanBecomingATextField() {
        // A typo'd "combobox" fell through to the default branch and shipped as a text box,
        // while the editor went on showing a dropdown.
        assertThrows(ApiException.class, () -> {
            Path pdf = onePagePdf();
            try {
                PdfTools.createForm(pdf, List.of(spec("combobox", "country", 200)));
            } finally {
                Files.deleteIfExists(pdf);
            }
        });
    }

    @Test
    void aPushButtonCarriesItsAction() throws Exception {
        // PDPushButton was the one AcroForm type with no path through the builder, and
        // setAction was called nowhere, so submit/reset/print/link buttons were impossible.
        Path pdf = onePagePdf();
        try {
            FormFieldSpec button = spec("pushbutton", "reset_all", 200);
            button.setAction("reset");
            button.setCaption("Start again");
            byte[] form = PdfTools.createForm(pdf, List.of(button));
            try (PDDocument doc = Loader.loadPDF(form)) {
                PDPushButton field = (PDPushButton) doc.getDocumentCatalog()
                        .getAcroForm().getField("reset_all");
                assertNotNull(field.getWidgets().get(0).getAction(), "button has no action");
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void aDevanagariCaptionSurvivesInsteadOfBeingDropped() throws Exception {
        // Helvetica is WinAnsi-only and toWinAnsi silently discarded everything above U+00FF,
        // so a Hindi caption was written as nothing at all and the response was still a 200.
        Path pdf = onePagePdf();
        try {
            FormFieldSpec text = spec("text", "naam", 200);
            text.setLabel("नाम");
            byte[] form = PdfTools.createForm(pdf, List.of(text));
            try (PDDocument doc = Loader.loadPDF(form)) {
                String rendered = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
                assertTrue(rendered.contains("नाम"),
                        "caption missing from the page, got: " + rendered.trim());
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void aCalculationSetsTheCalcOrderSoChainsEvaluateInOrder() throws Exception {
        // calculationOrder was declared, never populated and never set, so /CO was absent and a
        // subtotal-then-total chain evaluated in whatever order the reader picked.
        Path pdf = onePagePdf();
        try {
            FormFieldSpec total = spec("number", "total", 200);
            FormFieldSpec.CalculationSpec calc = new FormFieldSpec.CalculationSpec();
            calc.setFunction("SUM");
            calc.setFields(List.of("a", "b"));
            total.setCalculation(calc);
            byte[] form = PdfTools.createForm(pdf, List.of(total));
            try (PDDocument doc = Loader.loadPDF(form)) {
                assertFalse(doc.getDocumentCatalog().getAcroForm().getCalcOrder().isEmpty(),
                        "/CO was not written");
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void aConditionalFieldGetsShowHideLogicInTheDocument() throws Exception {
        // FormFieldSpec.condition was sent by the editor and read nowhere, so conditional
        // visibility worked in the app and was simply absent from the PDF.
        Path pdf = onePagePdf();
        try {
            FormFieldSpec parent = spec("dropdown", "country", 200);
            parent.setOptions(List.of("India", "Other"));
            FormFieldSpec child = spec("text", "state", 240);
            FormFieldSpec.Condition when = new FormFieldSpec.Condition();
            when.setParentField("country");
            when.setOperator("equals");
            when.setValue("India");
            child.setCondition(when);
            byte[] form = PdfTools.createForm(pdf, List.of(parent, child));
            try (PDDocument doc = Loader.loadPDF(form)) {
                PDComboBox combo = (PDComboBox) doc.getDocumentCatalog()
                        .getAcroForm().getField("country");
                assertNotNull(combo.getActions(), "parent has no additional actions");
                assertNotNull(combo.getActions().getV(),
                        "parent carries no script to show or hide its child");
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }
}

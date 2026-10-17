package com.vishnu.pdf_studio_api.pdfstudioapi.utils;

import com.vishnu.pdf_studio_api.pdfstudioapi.exception.ApiException;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;

import java.io.IOException;
import java.io.InputStream;

/**
 * Fonts for a generated form, and the decision of which one a document needs.
 *
 * <p>The builder only ever used Helvetica, a Standard-14 font limited to WinAnsi, and dropped
 * every character above U+00FF on its way to the page. The app ships Hindi and deliberately
 * localizes captions and option labels, so a Hindi author's form lost its captions entirely and
 * the response was a 200 saying it had worked.
 *
 * <p>Hind covers Latin and Devanagari and is the same face the app renders Hindi in, so a caption
 * in the finished PDF matches what the author saw in the editor. It is embedded only when the
 * document actually needs it, which keeps an English form byte-for-byte what it was.
 */
public final class FormFonts {
    /** Resource name under which the Unicode font is registered in the AcroForm's /DR. */
    public static final String UNICODE_RESOURCE = "HindU";

    /** Resource name of the Standard-14 face, unchanged. */
    public static final String LATIN_RESOURCE = "Helv";

    private static final String FONT_PATH = "/fonts/Hind-Regular.ttf";

    private final PDFont latin;
    private final PDFont unicode;

    private FormFonts(PDFont latin, PDFont unicode) {
        this.latin = latin;
        this.unicode = unicode;
    }

    /**
     * Prepares the fonts for [doc] and registers them in [acro]'s default resources.
     *
     * <p>[needsUnicode] decides whether the Unicode face is loaded at all. It is embedded in
     * full rather than subset: the person filling the form types text the builder has never
     * seen, and a subset would have no glyphs for it.
     */
    public static FormFonts prepare(PDDocument doc, PDAcroForm acro, boolean needsUnicode)
            throws IOException {
        PDFont latin = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDResources dr = new PDResources();
        dr.put(COSName.getPDFName(LATIN_RESOURCE), latin);

        PDFont unicode = null;
        if (needsUnicode) {
            unicode = loadUnicode(doc, false);
            dr.put(COSName.getPDFName(UNICODE_RESOURCE), unicode);
        }
        acro.setDefaultResources(dr);
        return new FormFonts(latin, unicode);
    }

    /**
     * The Unicode face for drawing static page content.
     *
     * <p>Subset on purpose, unlike the form-field copy: a caption is fixed text, so only the
     * glyphs it uses need to travel.
     */
    public static PDFont loadForPageText(PDDocument doc) throws IOException {
        return loadUnicode(doc, true);
    }

    private static PDFont loadUnicode(PDDocument doc, boolean subset) throws IOException {
        try (InputStream in = FormFonts.class.getResourceAsStream(FONT_PATH)) {
            if (in == null) {
                throw ApiException.serverError(
                        "The Unicode form font is missing from this build, so non-Latin text "
                                + "cannot be written. Expected " + FONT_PATH + " on the classpath.");
            }
            return PDType0Font.load(doc, in, subset);
        }
    }

    /** The /DA resource name a field should use, given the text it has to hold. */
    public String resourceFor(String text) {
        return unicode != null && !isLatin1(text) ? UNICODE_RESOURCE : LATIN_RESOURCE;
    }

    public PDFont latin() {
        return latin;
    }

    /** The Unicode face, or null when this document did not need one. */
    public PDFont unicode() {
        return unicode;
    }

    /** Whether every character in [s] fits WinAnsi, which is all Helvetica can encode. */
    public static boolean isLatin1(String s) {
        if (s == null) return true;
        return s.chars().allMatch(c -> c <= 0xFF);
    }

    /** Whether any of [texts] needs more than WinAnsi. */
    public static boolean anyNeedsUnicode(Iterable<String> texts) {
        for (String t : texts) {
            if (!isLatin1(t)) return true;
        }
        return false;
    }
}

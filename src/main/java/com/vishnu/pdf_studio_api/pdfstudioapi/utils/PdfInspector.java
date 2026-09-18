package com.vishnu.pdf_studio_api.pdfstudioapi.utils;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only reports about a PDF's permissions, safety, structure and objects.
 *
 * <p>Separate from {@link PdfTools} on purpose: everything here <em>reads</em> a document and
 * returns JSON-shaped maps, where {@code PdfTools} produces new PDFs. Keeping the two apart means
 * an inspector can never accidentally mutate the document it is describing, and a reader looking
 * for "what does this file contain?" has one place to look.
 *
 * <p>Each report is a plain {@code Map} so Spring serialises it directly — no DTO per report,
 * because the shapes are deep, heterogeneous and only ever consumed as JSON.
 */
public final class PdfInspector {

    private PdfInspector() {
    }

    // ── Permissions ──────────────────────────────────────────────────────────────

    /**
     * What the document's security handler allows.
     *
     * <p>Note the distinction the UI has to make clear: an <em>unencrypted</em> PDF grants
     * everything, so "printing allowed" on such a file means "nothing is stopping you", not "the
     * author permitted it". {@code encrypted} carries that context.
     */
    public static Map<String, Object> permissions(PDDocument doc) {
        AccessPermission p = doc.getCurrentAccessPermission();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("encrypted", doc.isEncrypted());
        out.put("ownerAccess", p.isOwnerPermission());

        Map<String, Object> allowed = new LinkedHashMap<>();
        allowed.put("print", p.canPrint());
        allowed.put("printFaithful", p.canPrintFaithful());
        allowed.put("modify", p.canModify());
        allowed.put("modifyAnnotations", p.canModifyAnnotations());
        allowed.put("fillInForm", p.canFillInForm());
        allowed.put("extractContent", p.canExtractContent());
        allowed.put("extractForAccessibility", p.canExtractForAccessibility());
        allowed.put("assembleDocument", p.canAssembleDocument());
        out.put("allowed", allowed);

        if (doc.isEncrypted() && doc.getEncryption() != null) {
            Map<String, Object> enc = new LinkedHashMap<>();
            enc.put("filter", doc.getEncryption().getFilter());
            enc.put("version", doc.getEncryption().getVersion());
            enc.put("revision", doc.getEncryption().getRevision());
            enc.put("keyLengthBits", doc.getEncryption().getLength());
            out.put("encryption", enc);
        }
        return out;
    }

    // ── Security scan ────────────────────────────────────────────────────────────

    /** One thing worth knowing about, with a severity the UI can sort and colour by. */
    private static Map<String, Object> finding(String id, String severity, String detail) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", id);
        f.put("severity", severity); // high | medium | low | info
        f.put("detail", detail);
        return f;
    }

    /**
     * Scans for active and privacy-relevant content: JavaScript, launch/URI actions, embedded
     * files, outbound links, forms, signatures and encryption.
     *
     * <p>The severities are deliberately conservative — this reports what is <em>present</em>, and
     * does not claim anything is malicious. A PDF with JavaScript is usually a form, not an
     * attack; the point is that the user knows before they forward it.
     */
    public static Map<String, Object> securityScan(PDDocument doc) throws IOException {
        List<Map<String, Object>> findings = new ArrayList<>();
        PDDocumentCatalog cat = doc.getDocumentCatalog();

        int jsCount = countJavaScript(cat);
        if (jsCount > 0) {
            findings.add(finding("javascript", "high",
                    jsCount + " document-level JavaScript entr" + (jsCount == 1 ? "y" : "ies")));
        }
        if (cat.getOpenAction() != null) {
            findings.add(finding("openAction", "medium",
                    "Runs an action when the document is opened"));
        }
        if (cat.getCOSObject().getDictionaryObject(COSName.getPDFName("AA")) != null) {
            findings.add(finding("additionalActions", "medium",
                    "Has additional (event-triggered) actions"));
        }

        List<String> embedded = embeddedFileNames(cat);
        if (!embedded.isEmpty()) {
            findings.add(finding("embeddedFiles", "high",
                    embedded.size() + " embedded file(s): " + String.join(", ", embedded)));
        }

        List<String> uris = externalUris(doc);
        if (!uris.isEmpty()) {
            findings.add(finding("externalLinks", "low",
                    uris.size() + " outbound link(s)"));
        }

        PDAcroForm form = cat.getAcroForm();
        if (form != null && !form.getFields().isEmpty()) {
            findings.add(finding("acroForm", "info",
                    form.getFields().size() + " form field(s)"));
            if (form.xfaIsDynamic()) {
                findings.add(finding("xfa", "medium",
                        "Contains a dynamic XFA form, which most viewers cannot render"));
            }
        }

        int sigs = doc.getSignatureDictionaries().size();
        if (sigs > 0) {
            findings.add(finding("signatures", "info", sigs + " digital signature(s)"));
        }

        if (doc.isEncrypted()) {
            findings.add(finding("encrypted", "info", "Document is encrypted"));
        }

        int annots = 0;
        for (PDPage page : doc.getPages()) annots += page.getAnnotations().size();
        if (annots > 0) {
            findings.add(finding("annotations", "low", annots + " annotation(s)"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("findings", findings);
        out.put("externalUris", uris);
        out.put("embeddedFiles", embedded);
        // A single number the UI can put at the top: the worst severity present.
        out.put("riskLevel", findings.stream().anyMatch(f -> "high".equals(f.get("severity"))) ? "high"
                : findings.stream().anyMatch(f -> "medium".equals(f.get("severity"))) ? "medium"
                : findings.isEmpty() ? "clean" : "low");
        return out;
    }

    private static int countJavaScript(PDDocumentCatalog cat) throws IOException {
        PDNameTreeNode<?> names = cat.getNames() == null ? null : cat.getNames().getJavaScript();
        if (names == null) return 0;
        return countNames(names);
    }

    private static int countNames(PDNameTreeNode<?> node) throws IOException {
        int n = node.getNames() == null ? 0 : node.getNames().size();
        if (node.getKids() != null) {
            for (PDNameTreeNode<?> kid : node.getKids()) n += countNames(kid);
        }
        return n;
    }

    private static List<String> embeddedFileNames(PDDocumentCatalog cat) throws IOException {
        List<String> out = new ArrayList<>();
        if (cat.getNames() == null || cat.getNames().getEmbeddedFiles() == null) return out;
        collectEmbedded(cat.getNames().getEmbeddedFiles(), out);
        return out;
    }

    private static void collectEmbedded(PDNameTreeNode<PDComplexFileSpecification> node,
                                        List<String> out) throws IOException {
        if (node.getNames() != null) out.addAll(node.getNames().keySet());
        if (node.getKids() != null) {
            for (PDNameTreeNode<PDComplexFileSpecification> kid : node.getKids()) {
                collectEmbedded(kid, out);
            }
        }
    }

    /** Every {@code /URI} action reachable from a link annotation, deduplicated, page order. */
    private static List<String> externalUris(PDDocument doc) throws IOException {
        List<String> out = new ArrayList<>();
        for (PDPage page : doc.getPages()) {
            for (PDAnnotation a : page.getAnnotations()) {
                if (!(a instanceof PDAnnotationLink link)) continue;
                PDAction action = link.getAction();
                if (action instanceof PDActionURI uri && uri.getURI() != null
                        && !out.contains(uri.getURI())) {
                    out.add(uri.getURI());
                }
            }
        }
        return out;
    }

    // ── Structure ────────────────────────────────────────────────────────────────

    /**
     * The document's skeleton: version, catalog flags, and per-page size, rotation, resource and
     * annotation counts.
     */
    public static Map<String, Object> structure(PDDocument doc) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        PDDocumentCatalog cat = doc.getDocumentCatalog();

        out.put("pdfVersion", doc.getVersion());
        out.put("pageCount", doc.getNumberOfPages());
        out.put("hasOutline", cat.getDocumentOutline() != null);
        out.put("hasAcroForm", cat.getAcroForm() != null);
        out.put("hasStructureTree", cat.getStructureTreeRoot() != null);
        out.put("hasXmpMetadata", cat.getMetadata() != null);
        out.put("pageLayout", cat.getPageLayout() == null ? null : cat.getPageLayout().stringValue());
        out.put("pageMode", cat.getPageMode() == null ? null : cat.getPageMode().stringValue());
        out.put("language", cat.getLanguage());

        PDDocumentInformation info = doc.getDocumentInformation();
        Map<String, Object> docInfo = new LinkedHashMap<>();
        docInfo.put("title", info.getTitle());
        docInfo.put("author", info.getAuthor());
        docInfo.put("producer", info.getProducer());
        docInfo.put("creator", info.getCreator());
        out.put("info", docInfo);

        List<Map<String, Object>> pages = new ArrayList<>();
        int index = 0;
        for (PDPage page : doc.getPages()) {
            pages.add(pageStructure(page, ++index));
        }
        out.put("pages", pages);
        return out;
    }

    private static Map<String, Object> pageStructure(PDPage page, int number) throws IOException {
        PDRectangle box = page.getMediaBox();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("page", number);
        p.put("widthPt", round(box.getWidth()));
        p.put("heightPt", round(box.getHeight()));
        p.put("rotation", page.getRotation());
        p.put("annotations", page.getAnnotations().size());

        PDResources res = page.getResources();
        List<String> fonts = new ArrayList<>();
        int images = 0;
        int forms = 0;
        if (res != null) {
            for (COSName name : res.getFontNames()) {
                try {
                    PDFont font = res.getFont(name);
                    if (font != null && !fonts.contains(font.getName())) fonts.add(font.getName());
                } catch (IOException ignored) {
                    // A broken font entry should not fail the whole report.
                }
            }
            for (COSName name : res.getXObjectNames()) {
                try {
                    PDXObject xo = res.getXObject(name);
                    if (xo instanceof PDImageXObject) images++;
                    else forms++;
                } catch (IOException ignored) {
                    // Same: an unreadable XObject is not worth losing the page over.
                }
            }
        }
        p.put("fonts", fonts);
        p.put("imageCount", images);
        p.put("formXObjectCount", forms);
        return p;
    }

    // ── Object explorer ──────────────────────────────────────────────────────────

    /**
     * A flat listing of the file's indirect objects, for someone who wants to see the COS layer.
     *
     * <p>Paged, because a real document runs to thousands of objects and neither the wire nor a
     * phone list wants all of them at once. Values are summarised rather than dumped in full: a
     * content stream's bytes are of no use in a list, its length is.
     */
    public static Map<String, Object> objects(PDDocument doc, int offset, int limit) {
        // The cross-reference table is the file's own index of its indirect objects. PDFBox 3
        // has no "give me every object" call — the object pool is populated lazily as objects are
        // dereferenced — so the xref is the authoritative list, and each key is resolved on
        // demand below. Sorted by object number so paging is stable between requests.
        List<COSObjectKey> keys = new ArrayList<>(doc.getDocument().getXrefTable().keySet());
        keys.sort(Comparator.comparingLong(COSObjectKey::getNumber));

        int from = Math.max(0, offset);
        int to = Math.min(keys.size(), from + Math.max(1, limit));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            COSObjectKey key = keys.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("number", key.getNumber());
            row.put("generation", key.getGeneration());
            COSBase v;
            try {
                COSObject o = doc.getDocument().getObjectFromPool(key);
                v = o == null ? null : o.getObject();
            } catch (Exception e) {
                // A damaged object should show as damaged, not sink the whole listing.
                v = null;
                row.put("error", e.getClass().getSimpleName());
            }
            row.put("type", typeOf(v));
            row.put("subtype", subtypeOf(v));
            row.put("summary", summarise(v));
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", keys.size());
        out.put("offset", from);
        out.put("objects", rows);
        return out;
    }

    private static String typeOf(COSBase v) {
        if (v instanceof COSStream) return "stream";
        if (v instanceof COSDictionary) return "dictionary";
        if (v instanceof COSArray) return "array";
        if (v instanceof COSString) return "string";
        if (v instanceof COSNumber) return "number";
        if (v instanceof COSBoolean) return "boolean";
        if (v instanceof COSName) return "name";
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    private static String subtypeOf(COSBase v) {
        if (!(v instanceof COSDictionary d)) return null;
        COSBase type = d.getDictionaryObject(COSName.TYPE);
        COSBase sub = d.getDictionaryObject(COSName.SUBTYPE);
        if (sub instanceof COSName n) return n.getName();
        if (type instanceof COSName n) return n.getName();
        return null;
    }

    private static String summarise(COSBase v) {
        if (v instanceof COSStream s) return s.getLength() + " bytes";
        if (v instanceof COSDictionary d) return d.size() + " entries";
        if (v instanceof COSArray a) return a.size() + " items";
        if (v instanceof COSString s) {
            String t = s.getString();
            return t.length() > 80 ? t.substring(0, 80) + "…" : t;
        }
        return v == null ? null : v.toString();
    }

    // ── PDF → JSON ───────────────────────────────────────────────────────────────

    /**
     * The document as structured JSON: metadata, then per page its size, text, annotations and
     * image count.
     *
     * <p>{@code includeText} is a real cost switch — extracting text runs the whole content stream
     * through {@link PDFTextStripper} page by page, which on a large scan is most of the request.
     */
    public static Map<String, Object> toJson(PDDocument doc, boolean includeText) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        PDDocumentInformation info = doc.getDocumentInformation();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", info.getTitle());
        meta.put("author", info.getAuthor());
        meta.put("subject", info.getSubject());
        meta.put("keywords", info.getKeywords());
        meta.put("creator", info.getCreator());
        meta.put("producer", info.getProducer());
        meta.put("pdfVersion", doc.getVersion());
        meta.put("encrypted", doc.isEncrypted());
        out.put("metadata", meta);

        PDFTextStripper stripper = includeText ? new PDFTextStripper() : null;
        List<Map<String, Object>> pages = new ArrayList<>();
        int number = 0;
        for (PDPage page : doc.getPages()) {
            number++;
            Map<String, Object> p = pageStructure(page, number);
            if (stripper != null) {
                stripper.setStartPage(number);
                stripper.setEndPage(number);
                p.put("text", stripper.getText(doc));
            }
            List<Map<String, Object>> annots = new ArrayList<>();
            for (PDAnnotation a : page.getAnnotations()) {
                Map<String, Object> an = new LinkedHashMap<>();
                an.put("subtype", a.getSubtype());
                an.put("contents", a.getContents());
                PDRectangle r = a.getRectangle();
                if (r != null) {
                    an.put("rect", List.of(round(r.getLowerLeftX()), round(r.getLowerLeftY()),
                            round(r.getUpperRightX()), round(r.getUpperRightY())));
                }
                annots.add(an);
            }
            p.put("annotationDetails", annots);
            pages.add(p);
        }
        out.put("pages", pages);

        PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
        if (form != null) {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (PDField f : form.getFieldTree()) {
                // Only terminal fields carry a value; a non-terminal one is just a naming node.
                if (f instanceof PDTerminalField) {
                    Map<String, Object> fm = new LinkedHashMap<>();
                    fm.put("name", f.getFullyQualifiedName());
                    fm.put("type", f.getFieldType());
                    fm.put("value", f.getValueAsString());
                    fields.add(fm);
                }
            }
            out.put("formFields", fields);
        }
        return out;
    }

    private static double round(float v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

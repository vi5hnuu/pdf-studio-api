package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.SanitizePdfRequest;
import com.vishnu.pdf_studio_api.pdfstudioapi.utils.PdfInspector;
import com.vishnu.pdf_studio_api.pdfstudioapi.utils.PdfTools;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDJavascriptNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the read-only inspectors and the sanitizer's new per-item options.
 *
 * <p>The inspectors exist so a user can answer "is this file safe to forward, and what is
 * actually in it?" without opening it. That makes a false *negative* the dangerous failure: a
 * report that misses the JavaScript is worse than no report, so each scan assertion here builds a
 * document that genuinely contains the thing and proves the scan names it.
 */
class PdfInspectorTest {

    /** A document carrying JavaScript, an attachment and an external link. */
    private Path loadedPdf() throws Exception {
        Path path = Files.createTempFile("inspector-test-", ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            PDDocumentCatalog cat = doc.getDocumentCatalog();
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(cat);

            PDJavascriptNameTreeNode js = new PDJavascriptNameTreeNode();
            Map<String, PDActionJavaScript> scripts = new HashMap<>();
            scripts.put("hello", new PDActionJavaScript("app.alert('hi');"));
            js.setNames(scripts);
            names.setJavascript(js);

            PDEmbeddedFilesNameTreeNode files = new PDEmbeddedFilesNameTreeNode();
            PDComplexFileSpecification spec = new PDComplexFileSpecification();
            spec.setFile("payload.txt");
            byte[] bytes = "attached".getBytes(StandardCharsets.UTF_8);
            PDEmbeddedFile embedded = new PDEmbeddedFile(doc, new ByteArrayInputStream(bytes));
            embedded.setSize(bytes.length);
            spec.setEmbeddedFile(embedded);
            Map<String, PDComplexFileSpecification> fileMap = new HashMap<>();
            fileMap.put("payload.txt", spec);
            files.setNames(fileMap);
            names.setEmbeddedFiles(files);

            cat.setNames(names);

            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(10, 10, 100, 20));
            PDActionURI uri = new PDActionURI();
            uri.setURI("https://example.com/tracker");
            link.setAction(uri);
            page.getAnnotations().add(link);

            doc.save(path.toFile());
        }
        return path;
    }

    private Path plainPdf() throws Exception {
        Path path = Files.createTempFile("inspector-plain-", ".pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(path.toFile());
        }
        return path;
    }

    @Test
    void theScanNamesTheJavaScriptTheAttachmentAndTheOutboundLink() throws Exception {
        Path pdf = loadedPdf();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            Map<String, Object> scan = PdfInspector.securityScan(doc);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> findings = (List<Map<String, Object>>) scan.get("findings");
            List<String> ids = findings.stream().map(f -> (String) f.get("id")).toList();

            assertTrue(ids.contains("javascript"), "JavaScript went unreported: " + ids);
            assertTrue(ids.contains("embeddedFiles"), "the attachment went unreported: " + ids);
            assertTrue(ids.contains("externalLinks"), "the outbound link went unreported: " + ids);
            assertEquals("high", scan.get("riskLevel"));
            assertEquals(List.of("https://example.com/tracker"), scan.get("externalUris"));
            assertEquals(List.of("payload.txt"), scan.get("embeddedFiles"));
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void aPlainDocumentScansClean() throws Exception {
        Path pdf = plainPdf();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            Map<String, Object> scan = PdfInspector.securityScan(doc);
            assertEquals("clean", scan.get("riskLevel"));
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void anUnencryptedDocumentReportsEveryPermissionAsAllowedAndSaysItIsNotEncrypted()
            throws Exception {
        // The distinction the UI has to carry: everything is "allowed" simply because nothing is
        // enforcing anything. Without the encrypted flag the report reads as the author's intent.
        Path pdf = plainPdf();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            Map<String, Object> perms = PdfInspector.permissions(doc);
            assertEquals(false, perms.get("encrypted"));

            @SuppressWarnings("unchecked")
            Map<String, Object> allowed = (Map<String, Object>) perms.get("allowed");
            assertEquals(true, allowed.get("print"));
            assertEquals(true, allowed.get("modify"));
            assertEquals(true, allowed.get("extractContent"));
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void theStructureReportCarriesPageGeometryAndCounts() throws Exception {
        Path pdf = loadedPdf();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            Map<String, Object> structure = PdfInspector.structure(doc);
            assertEquals(1, structure.get("pageCount"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pages = (List<Map<String, Object>>) structure.get("pages");
            assertEquals(1, pages.size());
            assertEquals(612.0, (Double) pages.get(0).get("widthPt"), 0.01);
            assertEquals(792.0, (Double) pages.get(0).get("heightPt"), 0.01);
            assertEquals(1, pages.get(0).get("annotations"));
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void theObjectListingPagesAndReportsTheRealTotal() throws Exception {
        Path pdf = loadedPdf();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            Map<String, Object> all = PdfInspector.objects(doc, 0, 500);
            int total = (Integer) all.get("total");
            assertTrue(total > 3, "expected a handful of objects, got " + total);

            Map<String, Object> page = PdfInspector.objects(doc, 1, 2);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("objects");
            assertEquals(2, rows.size());
            assertEquals(1, page.get("offset"));
            // The total must describe the document, not the page — otherwise the UI cannot tell
            // whether there is another page to ask for.
            assertEquals(total, page.get("total"));
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void jsonExportCarriesPerPageTextOnlyWhenAsked() throws Exception {
        Path pdf = plainPdf();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> withText =
                    (List<Map<String, Object>>) PdfInspector.toJson(doc, true).get("pages");
            assertTrue(withText.get(0).containsKey("text"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> without =
                    (List<Map<String, Object>>) PdfInspector.toJson(doc, false).get("pages");
            assertFalse(without.get(0).containsKey("text"));
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void sanitizeWithNoOptionsStillStripsEverythingItAlwaysDid() throws Exception {
        Path pdf = loadedPdf();
        try {
            byte[] cleaned = PdfTools.sanitizePdf(pdf);
            try (PDDocument doc = Loader.loadPDF(cleaned)) {
                Map<String, Object> scan = PdfInspector.securityScan(doc);
                assertEquals(List.of(), scan.get("embeddedFiles"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> findings =
                        (List<Map<String, Object>>) scan.get("findings");
                assertTrue(findings.stream().noneMatch(f -> "javascript".equals(f.get("id"))));
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void sanitizeCanKeepTheAttachmentWhileStillRemovingTheJavaScript() throws Exception {
        // This is the whole point of the options: sanitize used to be all-or-nothing, so someone
        // who wanted the script gone also lost the attachment they were forwarding.
        Path pdf = loadedPdf();
        try {
            SanitizePdfRequest opts = new SanitizePdfRequest();
            opts.setRemoveEmbeddedFiles(false);

            byte[] cleaned = PdfTools.sanitizePdf(pdf, opts);
            try (PDDocument doc = Loader.loadPDF(cleaned)) {
                Map<String, Object> scan = PdfInspector.securityScan(doc);
                assertEquals(List.of("payload.txt"), scan.get("embeddedFiles"),
                        "the attachment was supposed to survive");

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> findings =
                        (List<Map<String, Object>>) scan.get("findings");
                assertTrue(findings.stream().noneMatch(f -> "javascript".equals(f.get("id"))),
                        "the JavaScript should still have gone");
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void sanitizeCanStripOnlyTheOutboundLinksAndLeaveOtherAnnotationsAlone() throws Exception {
        Path pdf = loadedPdf();
        try {
            SanitizePdfRequest opts = new SanitizePdfRequest();
            opts.setRemoveExternalLinks(true);

            byte[] cleaned = PdfTools.sanitizePdf(pdf, opts);
            try (PDDocument doc = Loader.loadPDF(cleaned)) {
                Map<String, Object> scan = PdfInspector.securityScan(doc);
                assertEquals(List.of(), scan.get("externalUris"));
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void keepingMetadataIsPossibleEvenWhileRemovingActiveContent() throws Exception {
        Path pdf = Files.createTempFile("inspector-meta-", ".pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.getDocumentInformation().setAuthor("Ada");
            doc.save(pdf.toFile());
        }
        try {
            SanitizePdfRequest opts = new SanitizePdfRequest();
            opts.setRemoveMetadata(false);

            byte[] cleaned = PdfTools.sanitizePdf(pdf, opts);
            try (PDDocument doc = Loader.loadPDF(cleaned)) {
                assertEquals("Ada", doc.getDocumentInformation().getAuthor());
            }

            // …and the default still erases it.
            byte[] defaulted = PdfTools.sanitizePdf(pdf);
            try (PDDocument doc = Loader.loadPDF(defaulted)) {
                assertNull(doc.getDocumentInformation().getAuthor());
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void theOptionsPartDeserializesFromExactlyWhatTheAppSends() throws Exception {
        // The app builds this JSON by hand. Jackson's snake-case strategy turns
        // removeJavaScript into remove_java_script (not remove_javascript), and a mismatch would
        // silently fall back to the default — the flag the user turned off would still apply.
        String sent = """
                {"remove_java_script":false,"remove_embedded_files":false,"remove_actions":false,
                 "remove_metadata":false,"remove_annotations":true,"remove_external_links":true,
                 "remove_forms":true}""";

        SanitizePdfRequest req = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(sent, SanitizePdfRequest.class);

        assertFalse(req.isRemoveJavaScript());
        assertFalse(req.isRemoveEmbeddedFiles());
        assertFalse(req.isRemoveActions());
        assertFalse(req.isRemoveMetadata());
        assertTrue(req.isRemoveAnnotations());
        assertTrue(req.isRemoveExternalLinks());
        assertTrue(req.isRemoveForms());
    }

    @Test
    void theNamesTreeSurvivesWhenOnlyOneOfItsTwoBranchesIsBeingRemoved() throws Exception {
        // /Names carries both JavaScript and EmbeddedFiles. The original code dropped the whole
        // tree, which is only correct when both are going.
        Path pdf = loadedPdf();
        try {
            SanitizePdfRequest opts = new SanitizePdfRequest();
            opts.setRemoveJavaScript(false);

            byte[] cleaned = PdfTools.sanitizePdf(pdf, opts);
            try (PDDocument doc = Loader.loadPDF(cleaned)) {
                assertNotNull(doc.getDocumentCatalog().getCOSObject()
                        .getDictionaryObject(COSName.getPDFName("Names")));
                Map<String, Object> scan = PdfInspector.securityScan(doc);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> findings =
                        (List<Map<String, Object>>) scan.get("findings");
                assertTrue(findings.stream().anyMatch(f -> "javascript".equals(f.get("id"))),
                        "the JavaScript was supposed to be kept");
                assertEquals(List.of(), scan.get("embeddedFiles"));
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }
}

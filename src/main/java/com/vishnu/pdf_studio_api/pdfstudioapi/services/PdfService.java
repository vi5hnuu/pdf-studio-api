package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.RedactPdfRequest.RedactRegion;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.exception.ApiException;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.ColorModel;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.Placement;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.RangeModel;
import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.LoadProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.DownloadResponse;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.FileNames;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.OpenPdf;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.PdfDocuments;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.TempFiles;
import com.vishnu.pdf_studio_api.pdfstudioapi.utils.PdfTools;
import com.vishnu.pdf_studio_api.pdfstudioapi.utils.OfficeConvertTools;
import com.vishnu.pdf_studio_api.pdfstudioapi.validation.UploadValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class PdfService {

    private final LoadProperties loadProperties;

    /**
     * Also used outside the validation aspect, by tools that need to know <em>which</em> kind of
     * artwork arrived rather than only that it was acceptable — stamping, which draws a PDF and an
     * image by different routes.
     */
    private final UploadValidator uploadValidator;

    /**
     * Opens an upload as a temp-file-backed document.
     *
     * <p>Replaces {@code Loader.loadPDF(file.getBytes())}, which pulled the whole upload into the
     * heap and then let PDFBox hold the parsed document there too. Spring has already spilled the
     * multipart to disk, so this removes a copy rather than adding I/O, and lets PDFBox spill its
     * own working data for large documents.
     *
     */
    private OpenPdf openPdf(MultipartFile file) throws IOException {
        return openPdf(file, null);
    }

    private OpenPdf openPdf(MultipartFile file, String password) throws IOException {
        TempFiles.Handle handle = TempFiles.of(file, ".pdf");
        try {
            // PdfDocuments.load applies the page cap for every tool, including those that load
            // inside PdfTools — so it is not repeated here.
            PDDocument document = PdfDocuments.load(
                    handle.path(), loadProperties.getScratchFileThresholdBytes(), password);
            return new OpenPdf(handle, document);
        } catch (IOException | RuntimeException e) {
            handle.close();
            throw e;
        }
    }
    public ResponseEntity<Resource> mergePdf(String outFileName,List<MultipartFile> files) {
        if (outFileName == null ||  outFileName.isBlank() || outFileName.isEmpty()) outFileName = "images-pdf";

        try {
            final byte[] doc = PdfTools.mergePdf(outFileName,files);
            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity
                    .status(200)
                    .headers(headers)
                    .body(baR);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> reorderPdf(String outFileName, int[] order, MultipartFile file) {
        if (outFileName == null ||  outFileName.isBlank() || outFileName.isEmpty()) outFileName = "reorder-pdf";

        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            final byte[] doc = PdfTools.reorderPdf(upload.path(), order);
            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity
                    .status(200)
                    .headers(headers)
                    .body(baR);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> splitPdf(String outFileName, SplitType type, Integer fixed, List<RangeModel> ranges, MultipartFile file) {
        if(List.of(SplitType.SPLIT_BY_RANGE,SplitType.DELETE_PAGES).contains(type) && (ranges==null || ranges.isEmpty())) throw new IllegalArgumentException("invalid ranges.");
        // fixed is the divisor in Math.ceilDiv, so 0 threw ArithmeticException and a
        // negative value produced an unbounded loop; both surfaced as a 500.
        if (SplitType.FIXED_RANGE.equals(type) && (fixed == null || fixed < 1)) {
            throw ApiException.badRequest("Pages per file must be at least 1.");
        }

        if (outFileName == null ||  outFileName.isBlank() || outFileName.isEmpty()) outFileName = "split-pdf";

        try (OpenPdf opened = openPdf(file)) {
            final PDDocument document = opened.document();
            final byte[] doc = PdfTools.splitPdf(outFileName, type, fixed, ranges, document);
            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", type.equals(SplitType.DELETE_PAGES) ? "pdf" : "zip"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity
                    .status(200)
                    .headers(headers)
                    .body(baR);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> compressPdf(String outFileName, CompressionLevel level, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "compressed-pdf";
        if (level == null) level = CompressionLevel.RECOMMENDED;

        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            final byte[] doc = PdfTools.compressPdf(upload.path(), level);
            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> watermarkPdf(String outFileName, String text, Integer fontSize, ColorModel color, Float opacity, Double angle, Position vPos, Position hPos, Integer fromPage, Integer toPage, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "watermarked-pdf";
        if (text == null || text.isBlank()) text = "CONFIDENTIAL";
        if (fontSize == null) fontSize = 48;
        if (color == null) color = ColorModel.BLACK;
        if (opacity == null) opacity = 0.3f;
        if (angle == null) angle = 45.0;
        if (vPos == null) vPos = Position.CENTER;
        if (hPos == null) hPos = Position.CENTER;
        if (fromPage == null) fromPage = 0;

        try (OpenPdf opened = openPdf(file)) {
            final PDDocument document = opened.document();
            final byte[] doc = PdfTools.watermarkPdf(document, text, fontSize, color, opacity, angle, vPos, hPos, fromPage, toPage);
            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> extractText(MultipartFile file, String outFileName) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "extracted-text";

        try (OpenPdf opened = openPdf(file)) {
            final PDDocument document = opened.document();
            if (document.isEncrypted()) throw new Exception("document is protected, please remove password first");

            String text = PdfTools.extractText(document);
            if (text.isBlank()) {
                // A scanned PDF is images with no text layer. Returning an empty file with a
                // 200 left the user to guess; this names the actual reason.
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_TEXT_LAYER",
                        "This PDF has no selectable text — it looks like a scan. "
                                + "Text extraction needs a PDF with a text layer.");
            }
            byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayResource baR = new ByteArrayResource(textBytes);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "txt"));
            headers.setContentLength(textBytes.length);
            headers.setContentType(MediaType.TEXT_PLAIN);

            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> grayscalePdf(String outFileName, List<Integer> pages, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "grayscale-pdf";

        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            final byte[] doc = PdfTools.grayscalePdf(upload.path(), pages);
            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> cropPdf(String outFileName, Float marginTop, Float marginBottom, Float marginLeft, Float marginRight, List<Integer> pages, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "cropped-pdf";
        try (OpenPdf opened = openPdf(file)) {
            final PDDocument document = opened.document();
            final byte[] doc = PdfTools.cropPdf(document, marginTop, marginBottom, marginLeft, marginRight, pages);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<?> getMetadata(MultipartFile file) {
        try (OpenPdf opened = openPdf(file)) {
            final PDDocument document = opened.document();
            return ResponseEntity.ok(PdfTools.getMetadata(document));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> editMetadata(String outFileName, String title, String author, String subject, String keywords, String creator, String producer, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "edited-pdf";
        try (OpenPdf opened = openPdf(file)) {
            final PDDocument document = opened.document();
            final byte[] doc = PdfTools.editMetadata(document, title, author, subject, keywords, creator, producer);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> addHeaderFooter(String outFileName, String headerText, String footerText, Integer fontSize, com.vishnu.pdf_studio_api.pdfstudioapi.model.ColorModel color, org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName fontName, Integer fromPage, Integer toPage, Float topPadding, Float bottomPadding, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "header-footer-pdf";
        try (OpenPdf opened = openPdf(file)) {
            final PDDocument document = opened.document();
            if (toPage == null) toPage = document.getNumberOfPages() - 1;
            final byte[] doc = PdfTools.addHeaderFooter(document, headerText, footerText, fontSize, color, fontName, fromPage, toPage, topPadding, bottomPadding);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> repairPdf(String outFileName, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "repaired-pdf";
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            final byte[] doc = PdfTools.repairPdf(upload.path());
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> flattenPdf(String outFileName, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "flattened-pdf";
        try (OpenPdf opened = openPdf(file)) {
            final PDDocument document = opened.document();
            final byte[] doc = PdfTools.flattenPdf(document);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> addBlankPages(String outFileName, int[] positions, Float pageWidth, Float pageHeight, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "pdf-with-blanks";
        try (OpenPdf opened = openPdf(file)) {
            final PDDocument document = opened.document();
            final byte[] doc = PdfTools.addBlankPages(document, positions, pageWidth, pageHeight);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Stamps a PDF with artwork that may itself be a PDF or an image.
     *
     * <p>The stamp's kind is read from its bytes rather than its filename, and decides both the
     * temp file's extension and how the artwork is drawn.
     */
    public ResponseEntity<Resource> stampPdf(String outFileName, Float opacity, Integer fromPage, Integer toPage,
                                             Placement placement, MultipartFile sourceFile, MultipartFile stampFile) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "stamped-pdf";
        ArtworkKind stampKind = uploadValidator.pdfOrImage(stampFile, "stamp");
        String stampSuffix = stampKind == ArtworkKind.PDF ? ".pdf" : ".img";
        try (TempFiles.Handle source = TempFiles.of(sourceFile, ".pdf"); TempFiles.Handle stamp = TempFiles.of(stampFile, stampSuffix)) {
            final byte[] doc = PdfTools.stampPdf(source.path(), stamp.path(), stampKind, opacity, fromPage, toPage, placement);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Places an image at a user-defined position and size on one or more PDF pages.
     * Coordinates are fractions of page dimensions for device independence.
     */
    public ResponseEntity<Resource> placeImage(String outFileName, List<Integer> pages,
                                               Placement placement,
                                               MultipartFile pdfFile, MultipartFile imageFile) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "image-placed";
        try (TempFiles.Handle upload = TempFiles.of(pdfFile, ".pdf")) {
            byte[] result = PdfTools.placeImage(upload.path(), imageFile.getBytes(), pages, placement);
            ByteArrayResource baR = new ByteArrayResource(result);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(result.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> pdfToWord(String outFileName, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "converted";
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            byte[] result = OfficeConvertTools.pdfToDocx(upload.path());
            ByteArrayResource baR = new ByteArrayResource(result);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "docx"));
            headers.setContentLength(result.length);
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
            return ResponseEntity.ok().headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert PDF to Word: " + e.getMessage(), e);
        }
    }

    public ResponseEntity<Resource> pdfToPowerPoint(String outFileName, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "converted";
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            byte[] result = OfficeConvertTools.pdfToPptx(upload.path());
            ByteArrayResource baR = new ByteArrayResource(result);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pptx"));
            headers.setContentLength(result.length);
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
            return ResponseEntity.ok().headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert PDF to PowerPoint: " + e.getMessage(), e);
        }
    }

    public ResponseEntity<Resource> pdfToExcel(String outFileName, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "converted";
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            byte[] result = OfficeConvertTools.pdfToXlsx(upload.path());
            ByteArrayResource baR = new ByteArrayResource(result);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "xlsx"));
            headers.setContentLength(result.length);
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            return ResponseEntity.ok().headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert PDF to Excel: " + e.getMessage(), e);
        }
    }

    public ResponseEntity<Resource> redactPdf(String outFileName, List<RedactRegion> regions, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "redacted-pdf";
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            byte[] doc = PdfTools.redactPdf(upload.path(), regions);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> duplicatePages(String outFileName, java.util.Map<Integer, Integer> pageCounts, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "duplicated-pdf";
        if (pageCounts == null) pageCounts = java.util.Collections.emptyMap();
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            byte[] doc = PdfTools.duplicatePages(upload.path(), pageCounts);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Returns a JSON analysis report (page/word counts, blank/duplicate/landscape pages, etc.). */
    public ResponseEntity<?> analyzePdf(MultipartFile file) {
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            return ResponseEntity.ok(PdfTools.analyzePdf(upload.path()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Replaces a page range in the base with the pages of a second PDF. */
    public ResponseEntity<Resource> replacePages(String outFileName, Integer from, Integer to,
                                                 MultipartFile file, MultipartFile replacement) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "replaced-pages";
        int f = from == null ? 1 : from;
        int t = to == null ? f : to;
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf"); TempFiles.Handle replUpload = TempFiles.of(replacement, ".pdf")) {
            return pdfResponse(PdfTools.replacePages(upload.path(), replUpload.path(), f, t), outFileName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Extracts embedded font programs into a ZIP. */
    public ResponseEntity<Resource> extractFonts(MultipartFile file) {
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            return zipResponse(PdfTools.extractFonts(upload.path()), "extracted-fonts");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Flips pages horizontally or vertically. */
    public ResponseEntity<Resource> mirrorPdf(com.vishnu.pdf_studio_api.pdfstudioapi.enums.MirrorDirection direction,
                                              java.util.List<Integer> pages, MultipartFile file) {
        boolean horizontal = direction == null || direction == com.vishnu.pdf_studio_api.pdfstudioapi.enums.MirrorDirection.HORIZONTAL;
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            return pdfResponse(PdfTools.mirrorPdf(upload.path(), horizontal, pages), "mirrored");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Resizes every page to a standard size, scaling content to fit. */
    public ResponseEntity<Resource> resizePage(com.vishnu.pdf_studio_api.pdfstudioapi.enums.PageSizePreset size, List<Integer> pages, MultipartFile file) {
        if (size == null) size = com.vishnu.pdf_studio_api.pdfstudioapi.enums.PageSizePreset.A4;
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            return pdfResponse(PdfTools.resizePageSize(upload.path(), size.getWidth(), size.getHeight(), pages), "resized");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Scales page size and content uniformly by the given factor. */
    public ResponseEntity<Resource> scalePdf(Double scale, List<Integer> pages, MultipartFile file) {
        float f = (scale == null || scale <= 0) ? 1f : scale.floatValue();
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            return pdfResponse(PdfTools.scalePdf(upload.path(), f, pages), "scaled");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Inserts one PDF into another after the given page. */
    public ResponseEntity<Resource> insertPdf(String outFileName, Integer afterPage, MultipartFile file, MultipartFile insert) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "inserted";
        int pos = afterPage == null ? -1 : afterPage;
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf"); TempFiles.Handle insertUpload = TempFiles.of(insert, ".pdf")) {
            return pdfResponse(PdfTools.insertPdf(upload.path(), insertUpload.path(), pos), outFileName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Extracts embedded/attached files from a PDF, returned as a ZIP. */
    public ResponseEntity<Resource> extractEmbeddedFiles(MultipartFile file) {
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            return zipResponse(PdfTools.extractEmbeddedFiles(upload.path()), "embedded-files");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ResponseEntity<Resource> pdfResponse(byte[] doc, String name) {
        ByteArrayResource baR = new ByteArrayResource(doc);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(name, "document", "pdf"));
        headers.setContentLength(doc.length);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().headers(headers).body(baR);
    }

    /** Extracts embedded images from a PDF and returns them as a ZIP. */
    public ResponseEntity<Resource> extractImages(MultipartFile file) {
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            byte[] zip = PdfTools.extractImages(upload.path());
            return zipResponse(zip, "extracted-images");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Removes JavaScript, embedded files, actions and metadata from a PDF. */
    public ResponseEntity<Resource> sanitizePdf(MultipartFile file) {
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            byte[] doc = PdfTools.sanitizePdf(upload.path());
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(null, "sanitized", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Splits a PDF into parts no larger than the given size, returned as a ZIP. */
    public ResponseEntity<Resource> splitBySize(String outFileName, Double maxSizeMb, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "split-by-size";
        double mb = (maxSizeMb == null || maxSizeMb <= 0) ? 5.0 : maxSizeMb;
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            byte[] zip = PdfTools.splitBySize(upload.path(), (long) (mb * 1024 * 1024));
            return zipResponse(zip, outFileName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ResponseEntity<Resource> zipResponse(byte[] zip, String name) {
        ByteArrayResource baR = new ByteArrayResource(zip);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(name, "document", "zip"));
        headers.setContentLength(zip.length);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().headers(headers).body(baR);
    }

    /** Lists a PDF's existing AcroForm fields as JSON. */
    public ResponseEntity<?> getFormFields(MultipartFile file) {
        try (OpenPdf opened = openPdf(file)) {
            PDDocument doc = opened.document();
            return ResponseEntity.ok(PdfTools.getFormFields(doc));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Fills the supplied field values, then flattens the form. */
    public ResponseEntity<Resource> fillFlatten(String outFileName, java.util.Map<String, String> values, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "filled-flattened";
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            return pdfResponse(PdfTools.fillFlatten(upload.path(), values), outFileName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Strips document info + XMP metadata from a PDF. */
    public ResponseEntity<Resource> removeMetadata(MultipartFile file) {
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            byte[] doc = PdfTools.removeMetadata(upload.path());
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(null, "metadata-removed", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Adds real fillable AcroForm fields to a PDF from the supplied field specs. */
    public ResponseEntity<Resource> createForm(String outFileName,
                                               java.util.List<com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.CreateFormRequest.FormFieldSpec> fields,
                                               MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "fillable-form";
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            byte[] doc = PdfTools.createForm(upload.path(), fields);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Returns the bookmark tree as JSON — does not produce a file download. */
    public ResponseEntity<?> getBookmarks(MultipartFile file) {
        try (OpenPdf opened = openPdf(file)) {
            PDDocument doc = opened.document();
            return ResponseEntity.ok(PdfTools.getBookmarks(doc));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> editBookmarks(String outFileName, String bookmarksJson, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "bookmarked-pdf";
        try (OpenPdf opened = openPdf(file)) {
            PDDocument doc = opened.document();
            byte[] result = PdfTools.editBookmarks(doc, bookmarksJson);
            ByteArrayResource baR = new ByteArrayResource(result);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(result.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> removeBlankPages(String outFileName, float threshold, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "no-blank-pages";
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            var result = PdfTools.removeBlankPagesDetailed(upload.path(), threshold);
            byte[] doc = result.document();
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            // Without this the client cannot tell a no-op from a successful removal, since
            // both return 200 with a valid PDF.
            headers.add("X-Pages-Removed", String.valueOf(result.removed()));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> optimizePdf(String outFileName, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "optimized";
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            byte[] doc = PdfTools.optimizePdf(upload.path());
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> nUpPdf(String outFileName, int nUp, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = nUp + "-up";
        try (TempFiles.Handle upload = TempFiles.of(file, ".pdf")) {
            byte[] doc = PdfTools.nUpPdf(upload.path(), nUp);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> wordToPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> powerpointToPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> excelToPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> editPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> pdfToJpg(MultipartFile file, String outFileName, Quality quality, Boolean single, Direction direction, Integer imageGap) {
        if (file == null) throw new IllegalArgumentException("pdf document is required");

        if (outFileName == null ||  outFileName.isBlank() || outFileName.isEmpty()) outFileName = file.getOriginalFilename();
        if (single == null) single = true;
        if (direction == null) direction = Direction.VERTICAL;
        if (quality == null) quality = Quality.LOW;
        if (imageGap == null) imageGap = 0;

        try (OpenPdf opened = openPdf(file)) {
            final PDDocument document = opened.document();
            if (document.isEncrypted()) throw new Exception("document is protected please remove password first");

            byte[] imageBytes = PdfTools.pdfToImage(document, single, direction, quality, imageGap);

            ByteArrayResource baR = new ByteArrayResource(imageBytes);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", single ? "jpg" : "zip"));
            headers.setContentLength(imageBytes.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity
                    .status(200)
                    .headers(headers)
                    .body(baR);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> imageToPdf(String outFileName, ImagePageSize pageSize,
                                               PageOrientation orientation, Float marginPt,
                                               List<MultipartFile> files) {
        if (files.isEmpty()) throw new IllegalArgumentException("files cannot be empty");
        if (outFileName == null ||  outFileName.isBlank() || outFileName.isEmpty()) outFileName = "images-pdf";

        try {
            final byte[] doc = PdfTools.imagesToPdf(files, pageSize, orientation,
                    marginPt == null ? 0f : marginPt);
            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity
                    .status(200)
                    .headers(headers)
                    .body(baR);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> pageNumbers(MultipartFile file, String outFileName, Position vPos, Position hPos, Integer fromPage, Integer toPage, PageNoType pageNoType, ColorModel fillColor, Padding padding, Integer size, Standard14Fonts.FontName fontName) {
        outFileName = FileNames.safeBaseName(outFileName, FileNames.stripExtension(file.getOriginalFilename()));

        try (OpenPdf opened = openPdf(file)) {
            final PDDocument document = opened.document();
            if (toPage == null) toPage = document.getNumberOfPages() - 1;
            if (document.isEncrypted()) throw new Exception("document is protected please remove password first");

            byte[] doc = PdfTools.writePageNumbersToPages(document, vPos, hPos, fromPage, toPage, pageNoType, fillColor, padding, size, fontName);

            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity
                    .status(200)
                    .headers(headers)
                    .body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public ResponseEntity<Resource> rotatePdf(String outFileName,Integer fileAngle,Map<Integer,Integer> pageAngles,Boolean maintainRatio,MultipartFile file) {
        if (outFileName == null ||  outFileName.isBlank() || outFileName.isEmpty()) outFileName = "rotated_file";
        try(ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            OpenPdf opened = openPdf(file)){
            final PDDocument document = opened.document();
            final byte[] rotatedPdf = PdfTools.rotatePdf(document,fileAngle,pageAngles,maintainRatio);

            ByteArrayResource baR = new ByteArrayResource(rotatedPdf);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(baR.contentLength());
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity
                    .status(200)
                    .headers(headers)
                    .body(baR);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public ResponseEntity<Resource> unlockPdf(String outFileName,String password,MultipartFile file) throws InvalidPasswordException {
        outFileName = FileNames.safeBaseName(outFileName, FileNames.stripExtension(file.getOriginalFilename()));
        try (OpenPdf opened = openPdf(file, password)) {
            final PDDocument document = opened.document();
            // A bare Exception here became a 500. Uploading an unprotected file to the unlock
            // tool is an ordinary mistake and deserves an answer, not a server error.
            if (!document.isEncrypted()) {
                throw ApiException.badRequest("This PDF is not password-protected, so there is "
                        + "nothing to unlock.");
            }

            final byte[] protectedDocBytes = PdfTools.unprotectPdf(document);

            ByteArrayResource baR = new ByteArrayResource(protectedDocBytes);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(protectedDocBytes.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity
                    .status(200)
                    .headers(headers)
                    .body(baR);
        } catch (InvalidPasswordException e) {
            // The caller did supply a password; it simply was not the right one.
            throw ApiException.wrongPassword();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> protectPdf(String outFileName, String ownerPassword, String userPassword, Set<UserAccessPermission> userAccessPermissions, MultipartFile file) {
        outFileName = FileNames.safeBaseName(outFileName, FileNames.stripExtension(file.getOriginalFilename()));

        try (OpenPdf opened = openPdf(file)) {
            final PDDocument document = opened.document();
            final byte[] protectedDocBytes = PdfTools.protectPdf(document, ownerPassword, userPassword, userAccessPermissions);

            ByteArrayResource baR = new ByteArrayResource(protectedDocBytes);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, DownloadResponse.header(outFileName, "document", "pdf"));
            headers.setContentLength(protectedDocBytes.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity
                    .status(200)
                    .headers(headers)
                    .body(baR);
        }catch (InvalidPasswordException e){
          throw new RuntimeException("pdf is already protected");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> organizePdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> repairPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> signPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }


    public ResponseEntity<Resource> createPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> ocrPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }
}

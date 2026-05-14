package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.RedactPdfRequest.RedactRegion;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.ColorModel;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.RangeModel;
import com.vishnu.pdf_studio_api.pdfstudioapi.utils.PdfTools;
import com.vishnu.pdf_studio_api.pdfstudioapi.utils.OfficeConvertTools;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
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
public class PdfService {
    public ResponseEntity<Resource> mergePdf(String outFileName,List<MultipartFile> files) {
        if (outFileName == null ||  outFileName.isBlank() || outFileName.isEmpty()) outFileName = "images-pdf";

        try {
            final byte[] doc = PdfTools.mergePdf(outFileName,files);
            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
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

        try {
            final byte[] doc = PdfTools.reorderPdf(file, order);
            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
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
        if(SplitType.FIXED_RANGE.equals(type) && fixed==null) throw new IllegalArgumentException("invalid fixed value.");

        if (outFileName == null ||  outFileName.isBlank() || outFileName.isEmpty()) outFileName = "split-pdf";

        try (final PDDocument document = Loader.loadPDF(file.getBytes())) {
            final byte[] doc = PdfTools.splitPdf(outFileName, type, fixed, ranges, document);
            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.%s", outFileName,type.equals(SplitType.DELETE_PAGES) ? "pdf" : "zip"));
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

        try {
            final byte[] doc = PdfTools.compressPdf(file.getBytes(), level);
            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> watermarkPdf(String outFileName, String text, Integer fontSize, ColorModel color, Float opacity, Double angle, Postion vPos, Postion hPos, Integer fromPage, Integer toPage, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "watermarked-pdf";
        if (text == null || text.isBlank()) text = "CONFIDENTIAL";
        if (fontSize == null) fontSize = 48;
        if (color == null) color = ColorModel.BLACK;
        if (opacity == null) opacity = 0.3f;
        if (angle == null) angle = 45.0;
        if (vPos == null) vPos = Postion.CENTER;
        if (hPos == null) hPos = Postion.CENTER;
        if (fromPage == null) fromPage = 0;

        try (final PDDocument document = Loader.loadPDF(file.getBytes())) {
            final byte[] doc = PdfTools.watermarkPdf(document, text, fontSize, color, opacity, angle, vPos, hPos, fromPage, toPage);
            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> extractText(MultipartFile file, String outFileName) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "extracted-text";

        try (final PDDocument document = Loader.loadPDF(file.getBytes())) {
            if (document.isEncrypted()) throw new Exception("document is protected, please remove password first");

            String text = PdfTools.extractText(document);
            byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayResource baR = new ByteArrayResource(textBytes);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.txt", outFileName));
            headers.setContentLength(textBytes.length);
            headers.setContentType(MediaType.TEXT_PLAIN);

            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> grayscalePdf(String outFileName, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "grayscale-pdf";

        try {
            final byte[] doc = PdfTools.grayscalePdf(file.getBytes());
            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> cropPdf(String outFileName, Float marginTop, Float marginBottom, Float marginLeft, Float marginRight, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "cropped-pdf";
        try (final PDDocument document = Loader.loadPDF(file.getBytes())) {
            final byte[] doc = PdfTools.cropPdf(document, marginTop, marginBottom, marginLeft, marginRight);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<?> getMetadata(MultipartFile file) {
        try (final PDDocument document = Loader.loadPDF(file.getBytes())) {
            return ResponseEntity.ok(PdfTools.getMetadata(document));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> editMetadata(String outFileName, String title, String author, String subject, String keywords, String creator, String producer, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "edited-pdf";
        try (final PDDocument document = Loader.loadPDF(file.getBytes())) {
            final byte[] doc = PdfTools.editMetadata(document, title, author, subject, keywords, creator, producer);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> addHeaderFooter(String outFileName, String headerText, String footerText, Integer fontSize, com.vishnu.pdf_studio_api.pdfstudioapi.model.ColorModel color, org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName fontName, Integer fromPage, Integer toPage, Float topPadding, Float bottomPadding, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "header-footer-pdf";
        try (final PDDocument document = Loader.loadPDF(file.getBytes())) {
            if (toPage == null) toPage = document.getNumberOfPages() - 1;
            final byte[] doc = PdfTools.addHeaderFooter(document, headerText, footerText, fontSize, color, fontName, fromPage, toPage, topPadding, bottomPadding);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> repairPdf(String outFileName, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "repaired-pdf";
        try {
            final byte[] doc = PdfTools.repairPdf(file.getBytes());
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> flattenPdf(String outFileName, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "flattened-pdf";
        try (final PDDocument document = Loader.loadPDF(file.getBytes())) {
            final byte[] doc = PdfTools.flattenPdf(document);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> addBlankPages(String outFileName, int[] positions, Float pageWidth, Float pageHeight, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "pdf-with-blanks";
        try (final PDDocument document = Loader.loadPDF(file.getBytes())) {
            final byte[] doc = PdfTools.addBlankPages(document, positions, pageWidth, pageHeight);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> stampPdf(String outFileName, Float opacity, Integer fromPage, Integer toPage, MultipartFile sourceFile, MultipartFile stampFile) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "stamped-pdf";
        try {
            final byte[] doc = PdfTools.stampPdf(sourceFile.getBytes(), stampFile.getBytes(), opacity, fromPage, toPage);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Places an image at a user-defined position and size on a single PDF page.
     * Coordinates are fractions of page dimensions for device independence.
     */
    public ResponseEntity<Resource> placeImage(String outFileName, int page,
                                               float xFrac, float yFrac,
                                               float widthFrac, float heightFrac,
                                               MultipartFile pdfFile, MultipartFile imageFile) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "image-placed";
        try {
            byte[] result = PdfTools.placeImage(pdfFile.getBytes(), imageFile.getBytes(),
                    page, xFrac, yFrac, widthFrac, heightFrac);
            ByteArrayResource baR = new ByteArrayResource(result);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(result.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.status(200).headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> pdfToWord(String outFileName, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "converted";
        try {
            byte[] result = OfficeConvertTools.pdfToDocx(file.getBytes());
            ByteArrayResource baR = new ByteArrayResource(result);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + outFileName + ".docx");
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
        try {
            byte[] result = OfficeConvertTools.pdfToPptx(file.getBytes());
            ByteArrayResource baR = new ByteArrayResource(result);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + outFileName + ".pptx");
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
        try {
            byte[] result = OfficeConvertTools.pdfToXlsx(file.getBytes());
            ByteArrayResource baR = new ByteArrayResource(result);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + outFileName + ".xlsx");
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
        try {
            byte[] doc = PdfTools.redactPdf(file.getBytes(), regions);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> duplicatePages(String outFileName, List<Integer> pages, Integer count, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "duplicated-pdf";
        if (count == null || count < 1) count = 1;
        try {
            byte[] doc = PdfTools.duplicatePages(file.getBytes(), pages, count);
            ByteArrayResource baR = new ByteArrayResource(doc);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(doc.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok().headers(headers).body(baR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Returns the bookmark tree as JSON — does not produce a file download. */
    public ResponseEntity<?> getBookmarks(MultipartFile file) {
        try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
            return ResponseEntity.ok(PdfTools.getBookmarks(doc));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> editBookmarks(String outFileName, String bookmarksJson, MultipartFile file) {
        if (outFileName == null || outFileName.isBlank()) outFileName = "bookmarked-pdf";
        try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
            byte[] result = PdfTools.editBookmarks(doc, bookmarksJson);
            ByteArrayResource baR = new ByteArrayResource(result);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(result.length);
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

        try (final PDDocument document = Loader.loadPDF(file.getBytes())) {
            if (document.isEncrypted()) throw new Exception("document is protected please remove password first");

            byte[] imageBytes = PdfTools.pdfToImage(document, single, direction, quality, imageGap);

            ByteArrayResource baR = new ByteArrayResource(imageBytes);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, single ? String.format("attachment; filename=%s.jpg", outFileName) : String.format("attachment; filename=%s.zip", outFileName));
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

    public ResponseEntity<Resource> imageToPdf(String outFileName, List<MultipartFile> files) {
        if (files.isEmpty()) throw new IllegalArgumentException("files cannot be empty");
        if (outFileName == null ||  outFileName.isBlank() || outFileName.isEmpty()) outFileName = "images-pdf";

        try {
            final byte[] doc = PdfTools.imagesToPdf(files);
            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
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

    public ResponseEntity<Resource> pageNumbers(MultipartFile file, String outFileName, Postion vPos, Postion hPos, Integer fromPage, Integer toPage, PageNoType pageNoType, ColorModel fillColor, Padding padding, Integer size, Standard14Fonts.FontName fontName) {
        if (outFileName == null ||  outFileName.isBlank() || outFileName.isEmpty()) outFileName = file.getName();

        try (final PDDocument document = Loader.loadPDF(file.getBytes())) {
            if (toPage == null) toPage = document.getNumberOfPages() - 1;
            if (document.isEncrypted()) throw new Exception("document is protected please remove password first");

            byte[] doc = PdfTools.writePageNumbersToPages(document, vPos, hPos, fromPage, toPage, pageNoType, fillColor, padding, size, fontName);

            ByteArrayResource baR = new ByteArrayResource(doc);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
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
            final PDDocument document = Loader.loadPDF(file.getBytes())){
            final byte[] rotatedPdf = PdfTools.rotatePdf(document,fileAngle,pageAngles,maintainRatio);

            ByteArrayResource baR = new ByteArrayResource(rotatedPdf);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
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
        if (outFileName == null ||  outFileName.isBlank() || outFileName.isEmpty()) outFileName = file.getName();
        try (final PDDocument document = Loader.loadPDF(file.getBytes(),password)) {
            if(!document.isEncrypted()) throw new Exception("pdf is already un-protected");

            final byte[] protectedDocBytes = PdfTools.unprotectPdf(document);

            ByteArrayResource baR = new ByteArrayResource(protectedDocBytes);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
            headers.setContentLength(protectedDocBytes.length);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            return ResponseEntity
                    .status(200)
                    .headers(headers)
                    .body(baR);
        }catch (InvalidPasswordException e){
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResponseEntity<Resource> protectPdf(String outFileName, String ownerPassword, String userPassword, Set<UserAccessPermission> userAccessPermissions, MultipartFile file) {
        if (outFileName == null ||  outFileName.isBlank() || outFileName.isEmpty()) outFileName = file.getName();

        try (final PDDocument document = Loader.loadPDF(file.getBytes())) {
            final byte[] protectedDocBytes = PdfTools.protectPdf(document, ownerPassword, userPassword, userAccessPermissions);

            ByteArrayResource baR = new ByteArrayResource(protectedDocBytes);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, String.format("attachment; filename=%s.pdf", outFileName));
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

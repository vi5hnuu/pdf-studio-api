package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.ColorModel;
import com.vishnu.pdf_studio_api.pdfstudioapi.utils.PdfTools;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class PdfService {
    public ResponseEntity<Resource> mergePdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> splitPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> compressPdf(String outFileName, CompressionLevel level, MultipartFile file) {
        try (final PDDocument document = Loader.loadPDF(file.getBytes())) {

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> pdfToWord(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> pdfToPowerPoint(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> pdfToExcel(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
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

        if (outFileName == null) outFileName = file.getOriginalFilename();
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
        if (outFileName == null) outFileName = "images-pdf";

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
        if (outFileName == null) outFileName = file.getName();

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

    public ResponseEntity<Resource> waterMark(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> rotatePdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> unlockPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> protectPdf(String outFileName, String ownerPassword, String userPassword, Set<UserAccessPermission> userAccessPermissions, MultipartFile file) {
        if (outFileName == null) outFileName = file.getName();

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

    public ResponseEntity<Resource> extractTxt(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> createPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }

    public ResponseEntity<Resource> ocrPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
    }
}

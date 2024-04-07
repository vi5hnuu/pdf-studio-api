package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.ColorModel;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.RangeModel;
import com.vishnu.pdf_studio_api.pdfstudioapi.utils.PdfTools;
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

    public ResponseEntity<Resource> waterMark(@RequestPart() Object a, @RequestPart MultipartFile multipartFile) {
        return ResponseEntity.status(200).body(null);
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

package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.CompressPdfRequest;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.PdfService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/pdf-studio")
@RequiredArgsConstructor
public class PdfController {
    private final PdfService pdfService;

    @PostMapping(value = "/merge-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> mergePdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.mergePdf(a,multipartFile);
    }
    @PostMapping(value = "/split-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> splitPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.splitPdf(a,multipartFile);
    }
    @PostMapping(value = "/compress-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> compressPdf(@RequestPart("compress_info")CompressPdfRequest req, @RequestPart(value = "file") MultipartFile file){
        return pdfService.compressPdf(req.getOutFileName(),req.getCompressQuality(),file);
    }
    @PostMapping(value = "/pdf-to-word",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> pdfToWord(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.pdfToWord(a,multipartFile);
    }
    @PostMapping(value = "/pdf-to-powerpoint",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> pdfToPowerPoint(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.pdfToPowerPoint(a,multipartFile);
    }
    @PostMapping(value = "/pdf-to-excel",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> pdfToExcel(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.pdfToExcel(a,multipartFile);
    }
    @PostMapping(value = "/word-to-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> wordToPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.wordToPdf(a,multipartFile);
    }
    @PostMapping(value = "/powerpoint-to-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> powerpointToPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.powerpointToPdf(a,multipartFile);
    }
    @PostMapping(value = "/excel-to-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> excelToPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.excelToPdf(a,multipartFile);
    }
    @PostMapping(value = "/edit-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> editPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.editPdf(a,multipartFile);
    }
    @PostMapping(value = "/pdf-to-jpg",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> pdfToJpg(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.pdfToJpg(a,multipartFile);
    }
    @PostMapping(value = "/image-to-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> imageToPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.imageToPdf(a,multipartFile);
    }
    @PostMapping(value = "/page-numbers",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> pageNumbers(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.pageNumbers(a,multipartFile);
    }
    @PostMapping(value = "/watermark",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> waterMark(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.waterMark(a,multipartFile);
    }
    @PostMapping(value = "/rotate-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> rotatePdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.rotatePdf(a,multipartFile);
    }
    @PostMapping(value = "/unlock-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> unlockPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.unlockPdf(a,multipartFile);
    }
    @PostMapping(value = "/protect-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> protectPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.protectPdf(a,multipartFile);
    }
    @PostMapping(value = "/organize-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> organizePdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.organizePdf(a,multipartFile);
    }
    @PostMapping(value = "/repair-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> repairPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.repairPdf(a,multipartFile);
    }
    @PostMapping(value = "/sign-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> signPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.signPdf(a,multipartFile);
    }
    @PostMapping(value = "/extract-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> extractTxt(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.extractTxt(a,multipartFile);
    }
    @PostMapping(value = "/created-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> createPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.createPdf(a,multipartFile);
    }
    @PostMapping(value = "/ocr-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> ocrPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return pdfService.ocrPdf(a,multipartFile);
    }
}

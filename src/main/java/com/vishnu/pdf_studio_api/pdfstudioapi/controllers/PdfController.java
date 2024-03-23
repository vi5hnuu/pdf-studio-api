package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.PdfService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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
        return pdfService.compressPdf(req.getOutFileName(),req.getLevel(),file);
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
    public ResponseEntity<Resource> pdfToJpg(@RequestPart(value = "pdf-to-jpg-info",required = false) PdfToJpgRequest ptjI, @RequestPart("file") MultipartFile multipartFile){
        if(ptjI==null) ptjI=new PdfToJpgRequest();
        return pdfService.pdfToJpg(multipartFile,ptjI.getOutFileName(),ptjI.getQuality(),ptjI.getSingle(),ptjI.getDirection(),ptjI.getImageGap());
    }
    @PostMapping(value = "/image-to-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> imageToPdf(@RequestPart(value = "image-to-pdf-info",required = false) ImageToPdfRequest itp, @RequestPart("files") List<MultipartFile> files){
        if(itp==null) itp=new ImageToPdfRequest();
        return pdfService.imageToPdf(itp.getOutFileName(),files);
    }
    @PostMapping(value = "/page-numbers",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> pageNumbers(@RequestPart(value = "page-numbers-info",required = false)PageNumbersRequest pnr, @RequestPart("file") MultipartFile file){
        if(pnr==null) pnr=new PageNumbersRequest();
        return pdfService.pageNumbers(file,pnr.getOutFileName(),pnr.getVerticalPosition(),pnr.getHorizontalPosition(),pnr.getFromPage(),pnr.getToPage(),pnr.getPageNoType(),pnr.getFillColor(),pnr.getPadding(),pnr.getSize(),pnr.getFontName());
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
    public ResponseEntity<Resource> protectPdf(@Valid() @RequestPart("protect-pdf-info") ProtectPdfRequest ppr, @RequestPart("file") MultipartFile file) throws Exception {
        return pdfService.protectPdf(ppr.getOutFileName(),ppr.getOwnerPassword(),ppr.getUserPassword(),ppr.getUserAccessPermissions(),file);
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

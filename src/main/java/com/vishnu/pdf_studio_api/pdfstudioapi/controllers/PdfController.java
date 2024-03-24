package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.PdfService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
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
    public ResponseEntity<Resource> mergePdf(@RequestPart("merge-pdf-info") MergePdfRequest mpr, @RequestPart("files") List<MultipartFile> files) throws Exception {
        if (files.size()<2) throw new Exception("atleast 2 files are required to be merged");
        return pdfService.mergePdf(mpr.getOutFileName(),files);
    }
    @PostMapping(value = "/reorder-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> reorderPdf(@Valid() @RequestPart("reorder-pdf-info") ReorderPdfRequest rpr, @RequestPart("files") List<MultipartFile> files){
        return pdfService.reorderPdf(rpr.getOutFileName(),rpr.getOrder(),files);
    }
    @PostMapping(value = "/split-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> splitPdf(@RequestPart("split-pdf-info") SplitPdfRequest spr, @RequestPart("file") MultipartFile file){
        return pdfService.splitPdf(spr.getOutFileName(),spr.getType(),spr.getFixed(),spr.getRanges(),file);
    }
    @PostMapping(value = "/compress-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> compressPdf(@RequestPart("compress_info")CompressPdfRequest req, @RequestPart(value = "file") MultipartFile file){
        return pdfService.compressPdf(req.getOutFileName(),req.getLevel(),file);
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
    public ResponseEntity<Resource> waterMark(@RequestPart(value = "page-numbers-info",required = false)PageNumbersRequest pnr, @RequestPart MultipartFile multipartFile){
        return pdfService.waterMark(pnr,multipartFile);
    }
    @PostMapping(value = "/rotate-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> rotatePdf(@RequestPart("rotate-pdf-info") RotatePdfRequest rpr, @RequestPart("files") List<MultipartFile> files){
        return pdfService.rotatePdf(rpr.getOutFileName(),rpr.getAngle(),rpr.getFileAngle(),rpr.getPageAngles(),rpr.getPageNos(),rpr.getMaintainRatio(),files);
    }
    @PostMapping(value = "/unprotect-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> unlockPdf(@Valid() @RequestPart("unprotect-pdf-info") UnlockPdfRequest upr, @RequestPart("file") MultipartFile file) throws InvalidPasswordException {
        return pdfService.unlockPdf(upr.getOutFileName(),upr.getPassword(),file);
    }
    @PostMapping(value = "/protect-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> protectPdf(@Valid() @RequestPart("protect-pdf-info") ProtectPdfRequest ppr, @RequestPart("file") MultipartFile file) throws Exception {
        return pdfService.protectPdf(ppr.getOutFileName(),ppr.getOwnerPassword(),ppr.getUserPassword(),ppr.getUserAccessPermissions(),file);
    }
}

package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CompressionLevel;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.PdfService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
    public ResponseEntity<Resource> reorderPdf(@Valid() @RequestPart("reorder-pdf-info") ReorderPdfRequest rpr, @RequestPart("file") MultipartFile file){
        return pdfService.reorderPdf(rpr.getOutFileName(),rpr.getOrder(),file);
    }
    @PostMapping(value = "/split-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> splitPdf(@RequestPart("split-pdf-info") SplitPdfRequest spr, @RequestPart("file") MultipartFile file){
        return pdfService.splitPdf(spr.getOutFileName(),spr.getType(),spr.getFixed(),spr.getRanges(),file);
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
    @PostMapping(value = "/rotate-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> rotatePdf(@RequestPart("rotate-pdf-info") RotatePdfRequest rpr, @RequestPart("file") MultipartFile file){
        return pdfService.rotatePdf(rpr.getOutFileName(),rpr.getFileAngle(),rpr.getPageAngles(),rpr.getMaintainRatio(),file);
    }
    @PostMapping(value = "/unprotect-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> unlockPdf(@Valid() @RequestPart("unprotect-pdf-info") UnlockPdfRequest upr, @RequestPart("file") MultipartFile file) throws InvalidPasswordException {
        return pdfService.unlockPdf(upr.getOutFileName(),upr.getPassword(),file);
    }
    @PostMapping(value = "/protect-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> protectPdf(@Valid() @RequestPart("protect-pdf-info") ProtectPdfRequest ppr, @RequestPart("file") MultipartFile file) throws Exception {
        return pdfService.protectPdf(ppr.getOutFileName(),ppr.getOwnerPassword(),ppr.getUserPassword(),ppr.getUserAccessPermissions(),file);
    }

    @PostMapping(value = "/compress-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> compressPdf(@RequestPart(value = "compress-pdf-info", required = false) CompressPdfRequest cpr, @RequestPart("file") MultipartFile file) {
        if (cpr == null) cpr = new CompressPdfRequest(null, CompressionLevel.RECOMMENDED);
        return pdfService.compressPdf(cpr.getOutFileName(), cpr.getLevel(), file);
    }

    @PostMapping(value = "/watermark-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> watermarkPdf(@RequestPart(value = "watermark-pdf-info", required = false) WatermarkPdfRequest wpr, @RequestPart("file") MultipartFile file) {
        if (wpr == null) wpr = new WatermarkPdfRequest();
        return pdfService.watermarkPdf(wpr.getOutFileName(), wpr.getText(), wpr.getFontSize(), wpr.getColor(), wpr.getOpacity(), wpr.getAngle(), wpr.getVerticalPosition(), wpr.getHorizontalPosition(), wpr.getFromPage(), wpr.getToPage(), file);
    }

    @PostMapping(value = "/extract-text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> extractText(@RequestPart(value = "extract-text-info", required = false) ExtractTextRequest etr, @RequestPart("file") MultipartFile file) {
        String outFileName = etr != null ? etr.getOutFileName() : null;
        return pdfService.extractText(file, outFileName);
    }

    @PostMapping(value = "/grayscale-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> grayscalePdf(@RequestPart(value = "grayscale-pdf-info", required = false) GrayscalePdfRequest gpr, @RequestPart("file") MultipartFile file) {
        String outFileName = gpr != null ? gpr.getOutFileName() : null;
        return pdfService.grayscalePdf(outFileName, file);
    }

    @PostMapping(value = "/crop-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> cropPdf(@RequestPart(value = "crop-pdf-info", required = false) CropPdfRequest cpr, @RequestPart("file") MultipartFile file) {
        if (cpr == null) cpr = new CropPdfRequest();
        return pdfService.cropPdf(cpr.getOutFileName(), cpr.getMarginTop(), cpr.getMarginBottom(), cpr.getMarginLeft(), cpr.getMarginRight(), file);
    }

    @PostMapping(value = "/get-metadata", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> getMetadata(@RequestPart("file") MultipartFile file) {
        return pdfService.getMetadata(file);
    }

    @PostMapping(value = "/edit-metadata", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> editMetadata(@RequestPart(value = "edit-metadata-info", required = false) EditMetadataRequest emr, @RequestPart("file") MultipartFile file) {
        if (emr == null) emr = new EditMetadataRequest();
        return pdfService.editMetadata(emr.getOutFileName(), emr.getTitle(), emr.getAuthor(), emr.getSubject(), emr.getKeywords(), emr.getCreator(), emr.getProducer(), file);
    }

    @PostMapping(value = "/header-footer", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> headerFooter(@RequestPart(value = "header-footer-info", required = false) HeaderFooterRequest hfr, @RequestPart("file") MultipartFile file) {
        if (hfr == null) hfr = new HeaderFooterRequest();
        return pdfService.addHeaderFooter(hfr.getOutFileName(), hfr.getHeaderText(), hfr.getFooterText(), hfr.getFontSize(), hfr.getColor(), hfr.getFontName(), hfr.getFromPage(), hfr.getToPage(), hfr.getTopPadding(), hfr.getBottomPadding(), file);
    }

    @PostMapping(value = "/repair-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> repairPdf(@RequestPart(value = "repair-pdf-info", required = false) RepairPdfRequest rpr, @RequestPart("file") MultipartFile file) {
        String outFileName = rpr != null ? rpr.getOutFileName() : null;
        return pdfService.repairPdf(outFileName, file);
    }

    @PostMapping(value = "/flatten-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> flattenPdf(@RequestPart(value = "flatten-pdf-info", required = false) FlattenPdfRequest fpr, @RequestPart("file") MultipartFile file) {
        String outFileName = fpr != null ? fpr.getOutFileName() : null;
        return pdfService.flattenPdf(outFileName, file);
    }

    @PostMapping(value = "/add-blank-pages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> addBlankPages(@RequestPart(value = "add-blank-pages-info", required = false) AddBlankPagesRequest abpr, @RequestPart("file") MultipartFile file) {
        if (abpr == null) abpr = new AddBlankPagesRequest();
        return pdfService.addBlankPages(abpr.getOutFileName(), abpr.getPositions(), abpr.getPageWidth(), abpr.getPageHeight(), file);
    }

    @PostMapping(value = "/stamp-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> stampPdf(@RequestPart(value = "stamp-pdf-info", required = false) StampPdfRequest spr, @RequestPart("file") MultipartFile file, @RequestPart("stamp") MultipartFile stamp) {
        if (spr == null) spr = new StampPdfRequest();
        return pdfService.stampPdf(spr.getOutFileName(), spr.getOpacity(), spr.getFromPage(), spr.getToPage(), file, stamp);
    }
}

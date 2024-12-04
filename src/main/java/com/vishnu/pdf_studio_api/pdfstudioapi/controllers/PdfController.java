package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.Direction;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.Quality;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.PdfService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;


@RestController
@RequestMapping("/api/v1/pdf-studio")
@RequiredArgsConstructor
@CrossOrigin(origins = {"https://pdf-studio-vi.onrender.com"},exposedHeaders = {HttpHeaders.CONTENT_DISPOSITION})
public class PdfController {
    private final PdfService pdfService;

    @PostMapping(value = "/merge-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> mergePdf(@RequestPart("out_file_name") String outFileName, @RequestPart("files") List<MultipartFile> files) throws Exception {
        if (files.size()<2) throw new Exception("atleast 2 files are required to be merged");
        return pdfService.mergePdf(outFileName,files);
    }
    @PostMapping(value = "/reorder-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> reorderPdf(@RequestPart("out_file_name") String outFileName,@RequestPart("order") String order, @RequestPart("file") MultipartFile file){
        return pdfService.reorderPdf(outFileName,Arrays.stream(order.split(",")).mapToInt(Integer::parseInt).toArray(),file);
    }
    @PostMapping(value = "/split-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> splitPdf(@RequestPart("split-pdf-info") SplitPdfRequest spr, @RequestPart("file") MultipartFile file){
        return pdfService.splitPdf(spr.getOutFileName(),spr.getType(),spr.getFixed(),spr.getRanges(),file);
    }
    @PostMapping(value = "/pdf-to-jpg",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> pdfToJpg(@RequestPart("file") MultipartFile file,
                                             @Valid @RequestPart("meta") PdfToJpgRequest pdfToJpgRequest){
        return pdfService.pdfToJpg(file,pdfToJpgRequest.getOutFileName(),pdfToJpgRequest.getQuality(),pdfToJpgRequest.getSingle(),pdfToJpgRequest.getDirection(),pdfToJpgRequest.getImageGap());
    }
    @PostMapping(value = "/image-to-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> imageToPdf(@RequestPart(value = "out_file_name",required = false) String outFileName, @RequestPart("files") List<MultipartFile> files){
        return pdfService.imageToPdf(outFileName,files);
    }
    @PostMapping(value = "/page-numbers",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> pageNumbers(@RequestPart(value = "page-numbers-config")PageNumbersRequest pnr, @RequestPart("file") MultipartFile file){
        return pdfService.pageNumbers(file,pnr.getOutFileName(),pnr.getVerticalPosition(),pnr.getHorizontalPosition(),pnr.getFromPage(),pnr.getToPage(),pnr.getPageNoType(),pnr.getFillColor(),pnr.getPadding(),pnr.getSize(), Standard14Fonts.FontName.valueOf(pnr.getFontName()));
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
}

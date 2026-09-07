package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

import com.vishnu.pdf_studio_api.pdfstudioapi.annotation.ChargeCredits;
import com.vishnu.pdf_studio_api.pdfstudioapi.annotation.HeavyTool;
import com.vishnu.pdf_studio_api.pdfstudioapi.annotation.ValidateUpload;
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

    // Free "organize" basics carry no @ChargeCredits; heavy/premium tools are annotated
    // with their tool id, and the ChargeCreditsAspect debits the DB-priced cost on success.

    @ValidateUpload(minFiles = 2)
    @PostMapping(value = "/merge-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> mergePdf(@RequestPart("merge-pdf-info") MergePdfRequest mpr, @RequestPart("files") List<MultipartFile> files) {
        return pdfService.mergePdf(mpr.getOutFileName(),files);
    }
    @ValidateUpload
    @PostMapping(value = "/reorder-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> reorderPdf(@Valid() @RequestPart("reorder-pdf-info") ReorderPdfRequest rpr, @RequestPart("file") MultipartFile file){
        return pdfService.reorderPdf(rpr.getOutFileName(),rpr.getOrder(),file);
    }
    @ValidateUpload
    @PostMapping(value = "/split-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> splitPdf(@RequestPart("split-pdf-info") SplitPdfRequest spr, @RequestPart("file") MultipartFile file){
        return pdfService.splitPdf(spr.getOutFileName(),spr.getType(),spr.getFixed(),spr.getRanges(),file);
    }
    @ChargeCredits(tool = "pdf-to-jpg")
    @HeavyTool
    @ValidateUpload
    @PostMapping(value = "/pdf-to-jpg",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> pdfToJpg(@RequestPart(value = "pdf-to-jpg-info",required = false) PdfToJpgRequest ptjI, @RequestPart("file") MultipartFile multipartFile){
        if(ptjI==null) ptjI=new PdfToJpgRequest();
        return pdfService.pdfToJpg(multipartFile,ptjI.getOutFileName(),ptjI.getQuality(),ptjI.getSingle(),ptjI.getDirection(),ptjI.getImageGap(),ptjI.getPages());
    }
    @ChargeCredits(tool = "image-to-pdf")
    @HeavyTool
    @ValidateUpload(ValidateUpload.Kind.IMAGE)
    @PostMapping(value = "/image-to-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> imageToPdf(@RequestPart(value = "image-to-pdf-info",required = false) ImageToPdfRequest itp, @RequestPart("files") List<MultipartFile> files){
        if(itp==null) itp=new ImageToPdfRequest();
        return pdfService.imageToPdf(itp.getOutFileName(), itp.getPageSize(), itp.getOrientation(), itp.getMarginPt(), files);
    }
    @ChargeCredits(tool = "page-numbers")
    @ValidateUpload
    @PostMapping(value = "/page-numbers",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> pageNumbers(@RequestPart(value = "page-numbers-info",required = false)PageNumbersRequest pnr, @RequestPart("file") MultipartFile file){
        if(pnr==null) pnr=new PageNumbersRequest();
        return pdfService.pageNumbers(file,pnr.getOutFileName(),pnr.getVerticalPosition(),pnr.getHorizontalPosition(),pnr.getFromPage(),pnr.getToPage(),pnr.getPageNoType(),pnr.getFillColor(),pnr.getPadding(),pnr.getSize(),pnr.getFontName());
    }
    @ValidateUpload
    @PostMapping(value = "/rotate-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> rotatePdf(@RequestPart("rotate-pdf-info") RotatePdfRequest rpr, @RequestPart("file") MultipartFile file){
        return pdfService.rotatePdf(rpr.getOutFileName(),rpr.getFileAngle(),rpr.getPageAngles(),rpr.getMaintainRatio(),file);
    }
    @ChargeCredits(tool = "unprotect-pdf")
    @ValidateUpload
    @PostMapping(value = "/unprotect-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> unlockPdf(@Valid() @RequestPart("unprotect-pdf-info") UnlockPdfRequest upr, @RequestPart("file") MultipartFile file) throws InvalidPasswordException {
        return pdfService.unlockPdf(upr.getOutFileName(),upr.getPassword(),file);
    }
    @ChargeCredits(tool = "protect-pdf")
    @ValidateUpload
    @PostMapping(value = "/protect-pdf",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> protectPdf(@Valid() @RequestPart("protect-pdf-info") ProtectPdfRequest ppr, @RequestPart("file") MultipartFile file) throws Exception {
        return pdfService.protectPdf(ppr.getOutFileName(),ppr.getOwnerPassword(),ppr.getUserPassword(),ppr.getUserAccessPermissions(),file);
    }

    @ChargeCredits(tool = "compress-pdf")
    @HeavyTool
    @ValidateUpload
    @PostMapping(value = "/compress-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> compressPdf(@RequestPart(value = "compress-pdf-info", required = false) CompressPdfRequest cpr, @RequestPart("file") MultipartFile file) {
        if (cpr == null) cpr = new CompressPdfRequest(null, CompressionLevel.RECOMMENDED);
        return pdfService.compressPdf(cpr.getOutFileName(), cpr.getLevel(), file);
    }

    @ChargeCredits(tool = "watermark-pdf")
    @ValidateUpload
    @PostMapping(value = "/watermark-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> watermarkPdf(@RequestPart(value = "watermark-pdf-info", required = false) WatermarkPdfRequest wpr, @RequestPart("file") MultipartFile file) {
        if (wpr == null) wpr = new WatermarkPdfRequest();
        return pdfService.watermarkPdf(wpr.getOutFileName(), wpr.getText(), wpr.getFontSize(), wpr.getColor(), wpr.getOpacity(), wpr.getAngle(), wpr.getVerticalPosition(), wpr.getHorizontalPosition(), wpr.getFromPage(), wpr.getToPage(), file);
    }

    @ValidateUpload
    @PostMapping(value = "/extract-text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> extractText(@RequestPart(value = "extract-text-info", required = false) ExtractTextRequest etr, @RequestPart("file") MultipartFile file) {
        if (etr == null) etr = new ExtractTextRequest();
        return pdfService.extractText(file, etr.getOutFileName(), etr.getPages());
    }

    @ChargeCredits(tool = "grayscale-pdf")
    @HeavyTool
    @ValidateUpload
    @PostMapping(value = "/grayscale-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> grayscalePdf(@RequestPart(value = "grayscale-pdf-info", required = false) GrayscalePdfRequest gpr, @RequestPart("file") MultipartFile file) {
        if (gpr == null) gpr = new GrayscalePdfRequest();
        return pdfService.grayscalePdf(gpr.getOutFileName(), gpr.getPages(), file);
    }

    @ChargeCredits(tool = "crop-pdf")
    @ValidateUpload
    @PostMapping(value = "/crop-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> cropPdf(@RequestPart(value = "crop-pdf-info", required = false) CropPdfRequest cpr, @RequestPart("file") MultipartFile file) {
        if (cpr == null) cpr = new CropPdfRequest();
        return pdfService.cropPdf(cpr.getOutFileName(), cpr.getMarginTop(), cpr.getMarginBottom(), cpr.getMarginLeft(), cpr.getMarginRight(), cpr.keep(), cpr.overrides(), cpr.getPages(), file);
    }

    @ValidateUpload
    @PostMapping(value = "/get-metadata", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> getMetadata(@RequestPart("file") MultipartFile file) {
        return pdfService.getMetadata(file);
    }

    @ValidateUpload
    @PostMapping(value = "/edit-metadata", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> editMetadata(@RequestPart(value = "edit-metadata-info", required = false) EditMetadataRequest emr, @RequestPart("file") MultipartFile file) {
        if (emr == null) emr = new EditMetadataRequest();
        return pdfService.editMetadata(emr.getOutFileName(), emr.getTitle(), emr.getAuthor(), emr.getSubject(), emr.getKeywords(), emr.getCreator(), emr.getProducer(), file);
    }

    @ChargeCredits(tool = "header-footer")
    @ValidateUpload
    @PostMapping(value = "/header-footer", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> headerFooter(@RequestPart(value = "header-footer-info", required = false) HeaderFooterRequest hfr, @RequestPart("file") MultipartFile file) {
        if (hfr == null) hfr = new HeaderFooterRequest();
        return pdfService.addHeaderFooter(hfr.getOutFileName(), hfr.getHeaderText(), hfr.getFooterText(), hfr.getFontSize(), hfr.getColor(), hfr.getFontName(), hfr.getFromPage(), hfr.getToPage(), hfr.getTopPadding(), hfr.getBottomPadding(), file);
    }

    @ChargeCredits(tool = "repair-pdf")
    @HeavyTool
    @ValidateUpload
    @PostMapping(value = "/repair-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> repairPdf(@RequestPart(value = "repair-pdf-info", required = false) RepairPdfRequest rpr, @RequestPart("file") MultipartFile file) {
        String outFileName = rpr != null ? rpr.getOutFileName() : null;
        return pdfService.repairPdf(outFileName, file);
    }

    @ChargeCredits(tool = "flatten-pdf")
    @ValidateUpload
    @PostMapping(value = "/flatten-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> flattenPdf(@RequestPart(value = "flatten-pdf-info", required = false) FlattenPdfRequest fpr, @RequestPart("file") MultipartFile file) {
        String outFileName = fpr != null ? fpr.getOutFileName() : null;
        return pdfService.flattenPdf(outFileName, file);
    }

    @ValidateUpload
    @PostMapping(value = "/add-blank-pages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> addBlankPages(@RequestPart(value = "add-blank-pages-info", required = false) AddBlankPagesRequest abpr, @RequestPart("file") MultipartFile file) {
        if (abpr == null) abpr = new AddBlankPagesRequest();
        return pdfService.addBlankPages(abpr.getOutFileName(), abpr.getPositions(), abpr.getPageWidth(), abpr.getPageHeight(), file);
    }

    /** Stamps artwork — a one-page PDF or an image — over a range of pages. */
    @ChargeCredits(tool = "stamp-pdf")
    @ValidateUpload(artworkParts = "stamp")
    @PostMapping(value = "/stamp-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> stampPdf(@RequestPart(value = "stamp-pdf-info", required = false) StampPdfRequest spr, @RequestPart("file") MultipartFile file, @RequestPart("stamp") MultipartFile stamp) {
        if (spr == null) spr = new StampPdfRequest();
        return pdfService.stampPdf(spr.getOutFileName(), spr.getOpacity(), spr.getFromPage(), spr.getToPage(),
                spr.placement(), file, stamp);
    }

    /** Convert PDF to Word (.docx) — text-extraction based, preserves paragraph structure. */
    @ChargeCredits(tool = "pdf-to-word")
    @HeavyTool
    @ValidateUpload
    @PostMapping(value = "/pdf-to-word", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> pdfToWord(
            @RequestPart(value = "pdf-to-office-info", required = false) PdfToOfficeRequest req,
            @RequestPart("file") MultipartFile file) {
        String out = req != null ? req.getOutFileName() : null;
        return pdfService.pdfToWord(out, file);
    }

    /** Convert PDF to Excel (.xlsx) — text-extraction based, one sheet per page. */
    @ChargeCredits(tool = "pdf-to-excel")
    @HeavyTool
    @ValidateUpload
    @PostMapping(value = "/pdf-to-excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> pdfToExcel(
            @RequestPart(value = "pdf-to-office-info", required = false) PdfToOfficeRequest req,
            @RequestPart("file") MultipartFile file) {
        String out = req != null ? req.getOutFileName() : null;
        return pdfService.pdfToExcel(out, file);
    }

    /** Convert PDF to PowerPoint (.pptx) — one slide per page with extracted text. */
    @ChargeCredits(tool = "pdf-to-pptx")
    @HeavyTool
    @ValidateUpload
    @PostMapping(value = "/pdf-to-pptx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> pdfToPptx(
            @RequestPart(value = "pdf-to-office-info", required = false) PdfToOfficeRequest req,
            @RequestPart("file") MultipartFile file) {
        String out = req != null ? req.getOutFileName() : null;
        return pdfService.pdfToPowerPoint(out, file);
    }

    /**
     * Places an image at a user-defined position and size on one or more PDF pages.
     * Coordinates (x_frac, y_frac, width_frac, height_frac) are 0.0–1.0 fractions of page dimensions.
     * Proportions are preserved unless the caller asks for fit=STRETCH.
     */
    @ChargeCredits(tool = "place-image")
    @ValidateUpload(imageParts = "image")
    @PostMapping(value = "/place-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> placeImage(
            @RequestPart(value = "place-image-info") PlaceImageRequest req,
            @RequestPart("file") MultipartFile file,
            @RequestPart("image") MultipartFile image) {
        return pdfService.placeImage(req.getOutFileName(), req.targetPages(), req.placement(), file, image);
    }

    /** Permanently blacks out rectangular regions on specified pages. */
    @ChargeCredits(tool = "redact-pdf")
    @HeavyTool
    @ValidateUpload
    @PostMapping(value = "/redact-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> redactPdf(
            @RequestPart("redact-pdf-info") RedactPdfRequest req,
            @RequestPart("file") MultipartFile file) {
        return pdfService.redactPdf(req.getOutFileName(), req.getRegions(), file);
    }

    /** Duplicates selected pages (by 0-based index) {@code count} times after each occurrence. */
    @ValidateUpload
    @PostMapping(value = "/duplicate-pages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> duplicatePages(
            @RequestPart("duplicate-pages-info") DuplicatePagesRequest req,
            @RequestPart("file") MultipartFile file) {
        // Prefer per-page counts; fall back to the legacy flat pages + count pair.
        java.util.Map<Integer, Integer> counts = req.getPageCounts();
        if (counts == null || counts.isEmpty()) {
            counts = new java.util.HashMap<>();
            int c = (req.getCount() == null || req.getCount() < 1) ? 1 : req.getCount();
            if (req.getPages() != null) {
                for (Integer p : req.getPages()) counts.put(p, c);
            }
        }
        return pdfService.duplicatePages(req.getOutFileName(), counts, file);
    }

    /** Strips document info + XMP metadata from a PDF. */
    @ValidateUpload
    @PostMapping(value = "/remove-metadata", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> removeMetadata(@RequestPart("file") MultipartFile file) {
        return pdfService.removeMetadata(file);
    }

    /** Extracts embedded images from a PDF, returned as a ZIP. */
    @ChargeCredits(tool = "extract-images")
    @HeavyTool
    @ValidateUpload
    @PostMapping(value = "/extract-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> extractImages(@RequestPart("file") MultipartFile file) {
        return pdfService.extractImages(file);
    }

    /** Returns a JSON analysis report for a PDF. */
    @HeavyTool
    @ValidateUpload
    @PostMapping(value = "/analyze-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> analyzePdf(@RequestPart("file") MultipartFile file) {
        return pdfService.analyzePdf(file);
    }

    /** Replaces a page range in the base with the pages of a second PDF. */
    @ValidateUpload
    @PostMapping(value = "/replace-pages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> replacePages(
            @RequestPart(value = "replace-pages-info", required = false) ReplacePagesRequest req,
            @RequestPart("file") MultipartFile file,
            @RequestPart("replacement") MultipartFile replacement) {
        if (req == null) req = new ReplacePagesRequest();
        return pdfService.replacePages(req.getOutFileName(), req.getFrom(), req.getTo(), file, replacement);
    }

    /** Extracts embedded font programs from a PDF, returned as a ZIP. */
    @ChargeCredits(tool = "extract-fonts")
    @ValidateUpload
    @PostMapping(value = "/extract-fonts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> extractFonts(@RequestPart("file") MultipartFile file) {
        return pdfService.extractFonts(file);
    }

    /** Flips pages horizontally or vertically. */
    @ChargeCredits(tool = "mirror-pdf")
    @ValidateUpload
    @PostMapping(value = "/mirror-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> mirrorPdf(
            @RequestPart(value = "mirror-pdf-info", required = false) MirrorPdfRequest req,
            @RequestPart("file") MultipartFile file) {
        if (req == null) req = new MirrorPdfRequest();
        return pdfService.mirrorPdf(req.getDirection(), req.getPages(), file);
    }

    /** Resizes every page to a standard size (A4 / Letter / Legal). */
    @ChargeCredits(tool = "resize-page")
    @ValidateUpload
    @PostMapping(value = "/resize-page", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> resizePage(
            @RequestPart(value = "resize-page-info", required = false) ResizePageRequest req,
            @RequestPart("file") MultipartFile file) {
        if (req == null) req = new ResizePageRequest();
        return pdfService.resizePage(req.getSize(), req.getPages(), file);
    }

    /** Scales page size and content uniformly. */
    @ChargeCredits(tool = "scale-pdf")
    @ValidateUpload
    @PostMapping(value = "/scale-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> scalePdf(
            @RequestPart(value = "scale-pdf-info", required = false) ScalePdfRequest req,
            @RequestPart("file") MultipartFile file) {
        if (req == null) req = new ScalePdfRequest();
        return pdfService.scalePdf(req.getScale(), req.getPages(), file);
    }

    /** Inserts a second PDF into the first after a chosen page. */
    @ValidateUpload
    @PostMapping(value = "/insert-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> insertPdf(
            @RequestPart(value = "insert-pdf-info", required = false) InsertPdfRequest req,
            @RequestPart("file") MultipartFile file,
            @RequestPart("insert") MultipartFile insert) {
        if (req == null) req = new InsertPdfRequest();
        return pdfService.insertPdf(req.getOutFileName(), req.getAfterPage(), file, insert);
    }

    /** Extracts embedded/attached files from a PDF, returned as a ZIP. */
    @ChargeCredits(tool = "extract-embedded-files")
    @ValidateUpload
    @PostMapping(value = "/extract-embedded-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> extractEmbeddedFiles(@RequestPart("file") MultipartFile file) {
        return pdfService.extractEmbeddedFiles(file);
    }

    /** Removes JavaScript, embedded files, actions and metadata from a PDF. */
    @ValidateUpload
    @PostMapping(value = "/sanitize-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> sanitizePdf(@RequestPart("file") MultipartFile file) {
        return pdfService.sanitizePdf(file);
    }

    /** Splits a PDF into parts no larger than the requested size, returned as a ZIP. */
    @ChargeCredits(tool = "split-by-size")
    @HeavyTool
    @ValidateUpload
    @PostMapping(value = "/split-by-size", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> splitBySize(
            @RequestPart(value = "split-by-size-info", required = false) SplitBySizeRequest req,
            @RequestPart("file") MultipartFile file) {
        if (req == null) req = new SplitBySizeRequest();
        return pdfService.splitBySize(req.getOutFileName(), req.getMaxSizeMb(), file);
    }

    /** Lists a PDF's existing AcroForm fields as JSON. */
    @ValidateUpload
    @PostMapping(value = "/get-form-fields", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> getFormFields(@RequestPart("file") MultipartFile file) {
        return pdfService.getFormFields(file);
    }

    /** Fills the supplied field values then flattens the form. */
    @ChargeCredits(tool = "fill-flatten")
    @ValidateUpload
    @PostMapping(value = "/fill-flatten", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> fillFlatten(
            @RequestPart(value = "fill-flatten-info", required = false) FillFlattenRequest req,
            @RequestPart("file") MultipartFile file) {
        if (req == null) req = new FillFlattenRequest();
        return pdfService.fillFlatten(req.getOutFileName(), req.getValues(), file);
    }

    /** Turns a PDF into a fillable form by adding real AcroForm fields. */
    @ChargeCredits(tool = "create-form")
    @ValidateUpload
    @PostMapping(value = "/create-form", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> createForm(
            @RequestPart("create-form-info") CreateFormRequest req,
            @RequestPart("file") MultipartFile file) {
        return pdfService.createForm(req.getOutFileName(), req.getFields(), file);
    }

    /** Returns the PDF bookmark/outline tree as JSON — no file download. */
    @ValidateUpload
    @PostMapping(value = "/get-bookmarks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> getBookmarks(@RequestPart("file") MultipartFile file) {
        return pdfService.getBookmarks(file);
    }

    /** Replaces the PDF outline with the supplied bookmark tree (JSON-encoded string). */
    @ValidateUpload
    @PostMapping(value = "/edit-bookmarks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> editBookmarks(
            @RequestPart("edit-bookmarks-info") EditBookmarksRequest req,
            @RequestPart("file") MultipartFile file) {
        return pdfService.editBookmarks(req.getOutFileName(), req.getBookmarks(), file);
    }

    /** Removes pages whose pixel content is >= threshold fraction near-white (blank pages). */
    @HeavyTool
    @ValidateUpload
    @PostMapping(value = "/remove-blank-pages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> removeBlankPages(
            @RequestPart("remove-blank-pages-info") RemoveBlankPagesRequest req,
            @RequestPart("file") MultipartFile file) {
        return pdfService.removeBlankPages(req.getOutFileName(), req.getThreshold(), file);
    }

    /** Removes embedded thumbnails and re-saves to reduce file size. */
    @ChargeCredits(tool = "optimize-pdf")
    @HeavyTool
    @ValidateUpload
    @PostMapping(value = "/optimize-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> optimizePdf(
            @RequestPart("optimize-pdf-info") OptimizePdfRequest req,
            @RequestPart("file") MultipartFile file) {
        return pdfService.optimizePdf(req.getOutFileName(), file);
    }

    /** Tiles nUp (2 or 4) input pages onto each output sheet. */
    @ChargeCredits(tool = "n-up")
    @HeavyTool
    @ValidateUpload
    @PostMapping(value = "/n-up", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> nUp(
            @RequestPart("n-up-info") NUpRequest req,
            @RequestPart("file") MultipartFile file) {
        return pdfService.nUpPdf(req.getOutFileName(), req.getNUp(), file);
    }
}

package com.vishnu.pdf_studio_api.pdfstudioapi.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.RedactPdfRequest.RedactRegion;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.ColorModel;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.RangeModel;
import lombok.extern.slf4j.Slf4j;
import com.vishnu.pdf_studio_api.pdfstudioapi.exception.ApiException;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.PdfDocuments;
import org.springframework.http.HttpStatus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.CreateFormRequest.FormFieldSpec;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
public class PdfTools {
    public static byte[] compressPdf(Path pdfPath, CompressionLevel level) throws IOException {
        try (PDDocument document = PdfDocuments.load(pdfPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            for (int i = 0; i < document.getNumberOfPages(); i++) {
                compressImagesOnPage(document.getPage(i), document, level);
            }

            document.save(baos, CompressParameters.DEFAULT_COMPRESSION);
            return baos.toByteArray();
        }
    }


    /**
     * Clamps a client-supplied page range to what the document actually has.
     *
     * <p>Ranges arrive straight from the request. A negative {@code fromPage} indexed before
     * the first page and a {@code toPage} past the end ran off the tree — both surfaced as an
     * IndexOutOfBoundsException and a 500, rather than simply doing nothing outside the
     * document. Page numbering tightened this the most: its loop had no upper bound at all.
     *
     * @return {@code [from, to]} inclusive, or {@code null} when the range covers no page
     */
    static int[] clampRange(Integer fromPage, Integer toPage, int pageCount) {
        if (pageCount <= 0) return null;
        int from = fromPage == null ? 0 : Math.max(0, fromPage);
        int to = toPage == null ? pageCount - 1 : Math.min(toPage, pageCount - 1);
        if (from > to) return null;
        return new int[]{from, to};
    }

    public static byte[] watermarkPdf(PDDocument document, String text, int fontSize, ColorModel color, float opacity, double angleDegrees, Position vPos, Position hPos, Integer fromPage, Integer toPage) throws IOException {
        int[] range = clampRange(fromPage, toPage, document.getNumberOfPages());
        if (vPos == null) vPos = Position.CENTER;
        if (hPos == null) hPos = Position.CENTER;

        PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

        for (int pNo = range == null ? 1 : range[0]; range != null && pNo <= range[1]; pNo++) {
            PDPage page = document.getPage(pNo);
            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();

            float textWidth = font.getStringWidth(text) / 1000f * fontSize;
            float textHeight = font.getFontDescriptor().getFontBoundingBox().getHeight() / 1000f * fontSize;

            float x = switch (hPos) {
                case START -> 20f;
                case CENTER -> (pageWidth - textWidth) / 2f;
                case END -> pageWidth - textWidth - 20f;
            };
            float y = switch (vPos) {
                case START -> pageHeight - textHeight - 20f;
                case CENTER -> (pageHeight - textHeight) / 2f;
                case END -> 20f;
            };

            try (PDPageContentStream cs = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                gs.setNonStrokingAlphaConstant(opacity);
                gs.setAlphaSourceFlag(true);
                cs.setGraphicsStateParameters(gs);

                cs.beginText();
                cs.setFont(font, fontSize);
                cs.setNonStrokingColor(color.color());

                double radians = Math.toRadians(angleDegrees);
                Matrix matrix = new Matrix(
                    (float) Math.cos(radians), (float) Math.sin(radians),
                    -(float) Math.sin(radians), (float) Math.cos(radians),
                    x, y
                );
                cs.setTextMatrix(matrix);
                cs.showText(text);
                cs.endText();
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        document.save(baos, CompressParameters.NO_COMPRESSION);
        return baos.toByteArray();
    }

    public static String extractText(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        return stripper.getText(document);
    }

    public static byte[] grayscalePdf(Path pdfPath) throws IOException {
        try (PDDocument source = PdfDocuments.load(pdfPath);
             PDDocument output = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            PDFRenderer renderer = new PDFRenderer(source);

            for (int i = 0; i < source.getNumberOfPages(); i++) {
                PDRectangle mediaBox = source.getPage(i).getMediaBox();

                BufferedImage pageImage = renderer.renderImageWithDPI(i, 150, ImageType.GRAY);

                PDPage newPage = new PDPage(new PDRectangle(mediaBox.getWidth(), mediaBox.getHeight()));
                output.addPage(newPage);

                PDImageXObject pdImage = LosslessFactory.createFromImage(output, pageImage);
                try (PDPageContentStream cs = new PDPageContentStream(output, newPage)) {
                    cs.drawImage(pdImage, 0, 0, mediaBox.getWidth(), mediaBox.getHeight());
                }
            }

            output.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    private static void compressImagesOnPage(PDPage page, PDDocument document, CompressionLevel level) throws IOException {
        PDResources resources = page.getResources();
        if (resources == null) return;

        COSDictionary xobjectDict = resources.getCOSObject().getCOSDictionary(COSName.XOBJECT);
        if (xobjectDict == null) return;

        for (COSName name : resources.getXObjectNames()) {
            PDXObject xObject;
            try { xObject = resources.getXObject(name); } catch (IOException e) { continue; }
            if (!(xObject instanceof PDImageXObject image)) continue;

            BufferedImage src = image.getImage();
            if (src == null) continue;

            // Flatten to RGB — JPEG does not support transparency
            BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(src, 0, 0, null);
            g.dispose();

            ByteArrayOutputStream imgBaos = new ByteArrayOutputStream();
            ImageWriter jpegWriter = ImageIO.getImageWritersByFormatName("JPEG").next();
            ImageWriteParam jpegParams = jpegWriter.getDefaultWriteParam();
            jpegParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            jpegParams.setCompressionQuality(level.getQuality());
            try (MemoryCacheImageOutputStream memOut = new MemoryCacheImageOutputStream(imgBaos)) {
                jpegWriter.setOutput(memOut);
                jpegWriter.write(null, new IIOImage(rgb, null, null), jpegParams);
            }
            jpegWriter.dispose();

            PDImageXObject compressed = PDImageXObject.createFromByteArray(document, imgBaos.toByteArray(), name.getName());
            xobjectDict.setItem(name, compressed.getCOSObject());
        }
    }

    public static byte[] pdfToImage(PDDocument document, Boolean singleImage, Direction direction, Quality quality, Integer imageGap) throws IOException {
        if (singleImage) return pdfToSingleImage(document, direction, quality, imageGap);
        else return pdfToImagesZip(document, quality);
    }

    public static byte[] imagesToPdf(List<MultipartFile> files) throws Exception {
        // try-with-resources: the document was previously closed only on the success path, so
        // one unreadable image among many leaked the document and its scratch file.
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {

            for (MultipartFile file : files) {
                BufferedImage bimg = ImageIO.read(file.getInputStream());
                if (bimg == null) {
                    // ImageIO returns null rather than throwing for a format it cannot decode;
                    // reading width off it produced an opaque NullPointerException.
                    throw new IOException("Unsupported or corrupt image: " + file.getOriginalFilename());
                }
                float width = bimg.getWidth();
                float height = bimg.getHeight();

                PDPage page = new PDPage(new PDRectangle(width, height));
                document.addPage(page);

                PDImageXObject img = PDImageXObject.createFromByteArray(
                        document, file.getBytes(), file.getOriginalFilename());
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.drawImage(img, 0, 0);
                }
            }

            document.save(byteArrayOutputStream, CompressParameters.NO_COMPRESSION);
            return byteArrayOutputStream.toByteArray();
        }
    }

    public static byte[] reorderPdf(Path pdfPath, int[] order) throws Exception {
        // loadedDoc is in the resource list: it was previously loaded and never closed, leaking a
        // document (and its scratch file) on every call.
        try (PDDocument document = new PDDocument();
             PDDocument loadedDoc = PdfDocuments.load(pdfPath);
             ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            final PDPageTree pageTree = loadedDoc.getPages();

            for (final int pageIndex : order) {
                final PDPage page = pageTree.get(pageIndex);
                document.addPage(page);
            }

            document.save(byteArrayOutputStream, CompressParameters.NO_COMPRESSION);
            final byte[] bytes = byteArrayOutputStream.toByteArray();
            return bytes;
        }
    }

    public static byte[] pdfToSingleImage(PDDocument document, Direction direction, Quality quality, Integer imageGap) throws IOException {
        if (document == null) throw new IllegalArgumentException("pdf document is required");

        if (direction == null) direction = Direction.VERTICAL;
        if (quality == null) quality = Quality.LOW;
        if (imageGap == null) imageGap = 0;

        PDFRenderer pdfRenderer = new PDFRenderer(document);

        // Combine all pages into a single image
        BufferedImage combinedImage = null;
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
            BufferedImage pageImage = pdfRenderer.renderImageWithDPI(pageIndex, quality.getDpi(), ImageType.RGB);
            if (combinedImage == null) combinedImage = pageImage;
            else
                combinedImage = direction == Direction.VERTICAL ? combineImagesVertically(combinedImage, pageImage, imageGap) : combineImagesHorizontally(combinedImage, pageImage, imageGap);
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        if (combinedImage != null) ImageIO.write(combinedImage, "JPG", byteArrayOutputStream);
        final byte[] bytes = byteArrayOutputStream.toByteArray();
        byteArrayOutputStream.close();
        return bytes;
    }

    public static byte[] pdfToImagesZip(PDDocument document, Quality quality) throws IOException {
        if (document == null) throw new IllegalArgumentException("pdf document is required");
        if (quality == null) quality = Quality.LOW;

        try (ByteArrayOutputStream zipOutputStream = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(zipOutputStream);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);

            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                baos.reset();
                BufferedImage pageImage = pdfRenderer.renderImageWithDPI(pageIndex, quality.getDpi(), ImageType.RGB);
                ImageIO.write(pageImage, "JPG", baos);
                ZipEntry entry = new ZipEntry("page_" + (pageIndex + 1) + ".jpg");
                zip.putNextEntry(entry);
                zip.write(baos.toByteArray());
                zip.closeEntry();
            }
            zip.finish();
            return zipOutputStream.toByteArray();
        }
    }

    public static byte[] splitPdf(String outFileName, SplitType type, Integer fixed, List<RangeModel> ranges, PDDocument document) throws IOException {
        try (ByteArrayOutputStream zipOutputStream = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(zipOutputStream);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            final PDPageTree pt = document.getPages();
            if (type.equals(SplitType.FIXED_RANGE)) {
                int totalDocs = Math.ceilDiv(document.getNumberOfPages(), fixed);
                for (int docNo = 1; docNo <= totalDocs; docNo++) {
                    // try-with-resources: a failed save previously left this document open.
                    try (PDDocument docX = new PDDocument()) {
                        for (int pNo = (docNo - 1) * fixed; pNo < document.getNumberOfPages() && pNo < docNo * fixed; pNo++) {
                            docX.addPage(pt.get(pNo));
                        }
                        ZipEntry entry = new ZipEntry("range_" + (docNo) + ".pdf");
                        zip.putNextEntry(entry);
                        docX.save(baos);
                    }
                    zip.write(baos.toByteArray());
                    baos.reset();
                    zip.closeEntry();
                }
                zip.finish();
                return zipOutputStream.toByteArray();
            } else if (type.equals(SplitType.EXTRACT_ALL_PAGES)) {
                for (int pNo = 0; pNo < document.getNumberOfPages(); pNo++) {
                    PDDocument docX = new PDDocument();
                    docX.addPage(pt.get(pNo));
                    ZipEntry entry = new ZipEntry("range_" + (pNo + 1) + ".pdf");
                    zip.putNextEntry(entry);
                    docX.save(baos);
                    docX.close();
                    zip.write(baos.toByteArray());
                    baos.reset();
                    zip.closeEntry();
                }
                zip.finish();
                return zipOutputStream.toByteArray();
            } else if (type.equals(SplitType.SPLIT_BY_RANGE)) {
                for (int rangeNo = 0; rangeNo < ranges.size(); rangeNo++) {
                    final var range = ranges.get(rangeNo);

                    PDDocument docX = new PDDocument();
                    for (int pNo = range.getFrom(); pNo < document.getNumberOfPages() && pNo <= range.getTo(); pNo++) {
                        docX.addPage(pt.get(pNo));
                    }
                    ZipEntry entry = new ZipEntry("range_" + (rangeNo + 1) + ".pdf");
                    zip.putNextEntry(entry);
                    docX.save(baos);
                    docX.close();
                    zip.write(baos.toByteArray());
                    baos.reset();
                    zip.closeEntry();
                }
                zip.finish();
                return zipOutputStream.toByteArray();
            } else if (type.equals(SplitType.SPLIT_BY_BOOKMARK)) {
                // Collect top-level bookmark page indices using PDFBox outline API
                PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
                if (outline == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_BOOKMARKS",
                        "This PDF has no bookmarks to work with.");

                List<Integer> bookmarkPages = new ArrayList<>();
                PDOutlineItem item = outline.getFirstChild();
                while (item != null) {
                    PDPage page = item.findDestinationPage(document);
                    if (page != null) {
                        int idx = document.getPages().indexOf(page);
                        if (idx >= 0 && !bookmarkPages.contains(idx)) bookmarkPages.add(idx);
                    }
                    item = item.getNextSibling();
                }

                if (bookmarkPages.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_BOOKMARKS",
                        "This PDF has no bookmarks that point at a page.");
                java.util.Collections.sort(bookmarkPages);
                bookmarkPages.add(document.getNumberOfPages()); // sentinel for last chapter end

                for (int i = 0; i < bookmarkPages.size() - 1; i++) {
                    int fromPage = bookmarkPages.get(i);
                    int toPage = bookmarkPages.get(i + 1) - 1;
                    PDDocument docX = new PDDocument();
                    for (int pNo = Math.max(0, fromPage); pNo <= toPage && pNo < document.getNumberOfPages(); pNo++) {
                        docX.addPage(pt.get(pNo));
                    }
                    ZipEntry entry = new ZipEntry("chapter_" + (i + 1) + ".pdf");
                    zip.putNextEntry(entry);
                    docX.save(baos);
                    docX.close();
                    zip.write(baos.toByteArray());
                    baos.reset();
                    zip.closeEntry();
                }
                zip.finish();
                return zipOutputStream.toByteArray();
            } else {
                final var pagesToRemove= new ArrayList<PDPage>();
                for (int rangeNo = 0; rangeNo < ranges.size(); rangeNo++) {
                    final var range = ranges.get(rangeNo);
                    for (int pNo = range.getFrom(); pNo < document.getNumberOfPages() && pNo <= range.getTo(); pNo++) {
                        pagesToRemove.add(pt.get(pNo));
                    }
                }
                for(final var page : pagesToRemove){
                    document.removePage(page);
                }
                document.save(baos);
                return baos.toByteArray();
            }
        }
    }

    private static BufferedImage combineImagesVertically(BufferedImage image1, BufferedImage image2, Integer offset) {
        if (offset == null) offset = 0;

        int width = Math.max(image1.getWidth(), image2.getWidth());
        int height = image1.getHeight() + image2.getHeight() + offset;
        BufferedImage combinedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        combinedImage.createGraphics().drawImage(image1, 0, 0, null);
        combinedImage.createGraphics().drawImage(image2, 0, image1.getHeight() + offset, null);
        return combinedImage;
    }

    private static BufferedImage combineImagesHorizontally(BufferedImage image1, BufferedImage image2, Integer offset) {
        if (offset == null) offset = 0;

        int width = image1.getWidth() + image2.getWidth() + offset;
        int height = Math.max(image1.getHeight(), image2.getHeight());
        BufferedImage combinedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        combinedImage.createGraphics().drawImage(image1, 0, 0, null);
        combinedImage.createGraphics().drawImage(image2, image1.getWidth() + offset, 0, null);
        return combinedImage;
    }

    public static byte[] writePageNumbersToPages(PDDocument document, Position vPos, Position hPos, Integer fromPage, Integer toPage, PageNoType pageNoType, ColorModel fillColor, Padding padding, Integer size, Standard14Fonts.FontName fontName) throws IOException {
        // The request DTO applies these defaults in its all-args constructor, but the
        // controller builds it with the no-arg constructor when the (optional) info part is
        // absent — so every field arrives null and this dereferenced one of them. Defaulting
        // where the values are used means an omitted option cannot produce a 500.
        if (pageNoType == null) pageNoType = PageNoType.ONLY_X;
        if (fillColor == null) fillColor = ColorModel.BLACK;
        if (vPos == null) vPos = Position.END;
        if (hPos == null) hPos = Position.CENTER;
        if (size == null) size = 14;
        // addHeaderFooter already defaults its font this way; page numbering did not.
        if (fontName == null) fontName = Standard14Fonts.FontName.HELVETICA;
        if (padding == null) padding = new Padding(); // all-zero, its documented default

        final float defaultMargin=3.0f;
        final String toWrite = pageNoType.getType().replace("Y", String.valueOf(document.getNumberOfPages())).replace("_", " ");
        PDFont font = new PDType1Font(fontName); // You can change the font as needed

        int[] pageRange = clampRange(fromPage, toPage, document.getNumberOfPages());
        for (int pNo = pageRange == null ? 1 : pageRange[0]; pageRange != null && pNo <= pageRange[1]; pNo++) {
            final String text = toWrite.replace("X", String.valueOf(pNo + 1));
            float textWidth = font.getStringWidth(text) / 1000 * size;
            float textHeight = font.getFontDescriptor().getFontBoundingBox().getHeight() / 1000 * size;

            PDPage page = document.getPage(pNo);


            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();

            float xCoord = switch (hPos) {
                case Position.START -> padding.getLeft() + defaultMargin;
                case Position.CENTER -> Math.max(0, pageWidth / 2 - textWidth / 2.0f);
                case Position.END -> Math.max(0, pageWidth - textWidth - padding.getRight()-defaultMargin);
            };

            float yCoord = switch (vPos) {
                case Position.START -> pageHeight - textHeight - padding.getTop()-defaultMargin;
                case Position.CENTER -> pageHeight / 2-textHeight/2.0f;
                case Position.END -> padding.getBottom()+defaultMargin;
            };

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, false, true)) {
                contentStream.beginText();
                contentStream.setFont(font, size);
                contentStream.setNonStrokingColor(fillColor.color());
                contentStream.newLineAtOffset(xCoord, yCoord);
                contentStream.showText(text);
                contentStream.endText();
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        document.save(baos, CompressParameters.NO_COMPRESSION);
        final byte[] bytes = baos.toByteArray();
        baos.close();
        return bytes;
    }

    public static byte[] rotatePdf(PDDocument document,Integer fileAngle,Map<Integer,Integer> pagesAngle,Boolean maintainRatio) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            final PDPageTree pt = document.getPages();
            for(int pNo=0;pNo<document.getNumberOfPages();pNo++){
                final Integer rAngle= pagesAngle.getOrDefault(pNo,fileAngle!=null ? fileAngle : 0);
                final var page = pt.get(pNo);
                rotatePdfPage(document, page, rAngle,maintainRatio);
            }

            document.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }
    private static void rotatePdfPage(PDDocument document, PDPage page, Integer angle, Boolean maintainRatio) throws IOException {
        if(angle==null || angle%360==0) return;

        try (PDPageContentStream cs = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.PREPEND, false, false)) {

            Matrix matrix = Matrix.getRotateInstance(Math.toRadians(angle), 0, 0);
            PDRectangle cropBox = page.getCropBox();

            if (maintainRatio) {
                float tx = (cropBox.getLowerLeftX() + cropBox.getUpperRightX()) / 2;
                float ty = (cropBox.getLowerLeftY() + cropBox.getUpperRightY()) / 2;

                Rectangle rectangle = cropBox.transform(matrix).getBounds();
                float scale = Math.min(cropBox.getWidth() / (float) rectangle.getWidth(), cropBox.getHeight() / (float) rectangle.getHeight());

                cs.transform(Matrix.getTranslateInstance(tx, ty));
                cs.transform(matrix);
                cs.transform(Matrix.getScaleInstance(scale, scale));
                cs.transform(Matrix.getTranslateInstance(-tx, -ty));
            } else {
                cs.transform(matrix);
                Rectangle rectangle = cropBox.transform(matrix).getBounds();
                PDRectangle newBox = new PDRectangle((float) rectangle.getX(), (float) rectangle.getY(), (float) rectangle.getWidth(), (float) rectangle.getHeight());
                page.setCropBox(newBox);
                page.setMediaBox(newBox);
            }
        }
    }

    public static byte[] protectPdf(PDDocument document, String ownerPassword, String userPassword, Set<UserAccessPermission> userAccessPermissions) throws Exception {
        final AccessPermission ap = getUserAccessPermission(userAccessPermissions);
        final StandardProtectionPolicy spp = new StandardProtectionPolicy(ownerPassword, userPassword, ap);
        spp.setEncryptionKeyLength(256);
        document.protect(spp);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        document.save(baos, CompressParameters.NO_COMPRESSION);
        final byte[] bytes = baos.toByteArray();
        baos.close();
        return bytes;
    }

    public static byte[] unprotectPdf(PDDocument document) throws Exception {
        AccessPermission accessPermission = document.getCurrentAccessPermission();
        if (accessPermission.isOwnerPermission()) {
            document.setAllSecurityToBeRemoved(true);
        } else {
            throw new Exception("you do not have owner permission to unprotect it.");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        document.save(baos, CompressParameters.NO_COMPRESSION);
        final byte[] bytes = baos.toByteArray();
        baos.close();
        return bytes;
    }

    private static AccessPermission getUserAccessPermission(Set<UserAccessPermission> userAccessPermissions) {
        final AccessPermission ap = new AccessPermission();//default user has owner permission
        if (!userAccessPermissions.isEmpty()) {
            ap.setCanFillInForm(userAccessPermissions.contains(UserAccessPermission.FILL_IN_FORM));
            ap.setCanAssembleDocument(userAccessPermissions.contains(UserAccessPermission.ASSEMBLE_DOCUMENT));
            ap.setCanExtractContent(userAccessPermissions.contains(UserAccessPermission.EXTRACT));
            ap.setCanModify(userAccessPermissions.contains(UserAccessPermission.MODIFICATION));
            ap.setCanPrint(userAccessPermissions.contains(UserAccessPermission.PRINT));
            ap.setCanExtractForAccessibility(userAccessPermissions.contains(UserAccessPermission.EXTRACT_FOR_ACCESSIBILITY));
            ap.setCanModifyAnnotations(userAccessPermissions.contains(UserAccessPermission.MODIFY_ANNOTATIONS));
            ap.setCanPrintFaithful(userAccessPermissions.contains(UserAccessPermission.FAITHFUL_PRINT));
            if (userAccessPermissions.contains(UserAccessPermission.READ_ONLY)) ap.setReadOnly();
        }
        return ap;
    }

    public static byte[] cropPdf(PDDocument document, Float marginTop, Float marginBottom, Float marginLeft, Float marginRight) throws IOException {
        if (marginTop == null) marginTop = 0f;
        if (marginBottom == null) marginBottom = 0f;
        if (marginLeft == null) marginLeft = 0f;
        if (marginRight == null) marginRight = 0f;

        for (int i = 0; i < document.getNumberOfPages(); i++) {
            PDPage page = document.getPage(i);
            PDRectangle mb = page.getMediaBox();
            float llx = mb.getLowerLeftX() + marginLeft;
            float lly = mb.getLowerLeftY() + marginBottom;
            float width = mb.getWidth() - marginLeft - marginRight;
            float height = mb.getHeight() - marginTop - marginBottom;
            if (width > 0 && height > 0) {
                page.setCropBox(new PDRectangle(llx, lly, width, height));
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        document.save(baos, CompressParameters.NO_COMPRESSION);
        return baos.toByteArray();
    }

    public static Map<String, String> getMetadata(PDDocument document) {
        PDDocumentInformation info = document.getDocumentInformation();
        Map<String, String> result = new java.util.LinkedHashMap<>();
        result.put("title", info.getTitle());
        result.put("author", info.getAuthor());
        result.put("subject", info.getSubject());
        result.put("keywords", info.getKeywords());
        result.put("creator", info.getCreator());
        result.put("producer", info.getProducer());
        result.put("page_count", String.valueOf(document.getNumberOfPages()));
        if (info.getCreationDate() != null) result.put("creation_date", info.getCreationDate().getTime().toString());
        if (info.getModificationDate() != null) result.put("modification_date", info.getModificationDate().getTime().toString());
        return result;
    }

    public static byte[] editMetadata(PDDocument document, String title, String author, String subject, String keywords, String creator, String producer) throws IOException {
        PDDocumentInformation info = document.getDocumentInformation();
        if (title != null) info.setTitle(title);
        if (author != null) info.setAuthor(author);
        if (subject != null) info.setSubject(subject);
        if (keywords != null) info.setKeywords(keywords);
        if (creator != null) info.setCreator(creator);
        if (producer != null) info.setProducer(producer);
        document.setDocumentInformation(info);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        document.save(baos, CompressParameters.NO_COMPRESSION);
        return baos.toByteArray();
    }

    public static byte[] addHeaderFooter(PDDocument document, String headerText, String footerText, Integer fontSize, ColorModel color, Standard14Fonts.FontName fontName, Integer fromPage, Integer toPage, Float topPadding, Float bottomPadding) throws IOException {
        if (fontSize == null) fontSize = 10;
        if (color == null) color = ColorModel.BLACK;
        if (fontName == null) fontName = Standard14Fonts.FontName.HELVETICA;
        if (topPadding == null) topPadding = 10f;
        if (bottomPadding == null) bottomPadding = 10f;
        if (fromPage == null) fromPage = 0;
        if (toPage == null) toPage = document.getNumberOfPages() - 1;

        PDFont font = new PDType1Font(fontName);
        int totalPages = document.getNumberOfPages();

        for (int pNo = Math.max(0, fromPage); pNo <= toPage && pNo < totalPages; pNo++) {
            PDPage page = document.getPage(pNo);
            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();

            String resolvedHeader = headerText != null ? resolveDsl(headerText, pNo + 1, totalPages) : null;
            String resolvedFooter = footerText != null ? resolveDsl(footerText, pNo + 1, totalPages) : null;

            try (PDPageContentStream cs = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.setNonStrokingColor(color.color());
                cs.setFont(font, fontSize);

                if (resolvedHeader != null && !resolvedHeader.isBlank()) {
                    float textWidth = font.getStringWidth(resolvedHeader) / 1000f * fontSize;
                    float x = (pageWidth - textWidth) / 2f;
                    float y = pageHeight - topPadding - fontSize;
                    cs.beginText();
                    cs.newLineAtOffset(x, y);
                    cs.showText(resolvedHeader);
                    cs.endText();
                }

                if (resolvedFooter != null && !resolvedFooter.isBlank()) {
                    float textWidth = font.getStringWidth(resolvedFooter) / 1000f * fontSize;
                    float x = (pageWidth - textWidth) / 2f;
                    float y = bottomPadding;
                    cs.beginText();
                    cs.newLineAtOffset(x, y);
                    cs.showText(resolvedFooter);
                    cs.endText();
                }
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        document.save(baos, CompressParameters.NO_COMPRESSION);
        return baos.toByteArray();
    }

    /**
     * Resolves DSL tokens in header/footer text.
     * Supported tokens:
     *   {{page}}            → current page number (1-based)
     *   {{total}}           → total page count
     *   {{page_of_total}}   → "3 of 10"
     *   {{page/total}}      → "3/10"
     *   {{roman}}           → lowercase roman numeral (i, ii, iii …)
     *   {{ROMAN}}           → uppercase roman numeral (I, II, III …)
     */
    private static String resolveDsl(String template, int page, int total) {
        return template
                .replace("{{page}}", String.valueOf(page))
                .replace("{{total}}", String.valueOf(total))
                .replace("{{page_of_total}}", page + " of " + total)
                .replace("{{page/total}}", page + "/" + total)
                .replace("{{roman}}", toRoman(page).toLowerCase(java.util.Locale.ROOT))
                .replace("{{ROMAN}}", toRoman(page));
    }

    private static String toRoman(int n) {
        if (n <= 0) return String.valueOf(n);
        String[] thousands = {"", "M", "MM", "MMM"};
        String[] hundreds  = {"", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM"};
        String[] tens      = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
        String[] ones      = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        return thousands[Math.min(n / 1000, 3)]
             + hundreds[(n % 1000) / 100]
             + tens[(n % 100) / 10]
             + ones[n % 10];
    }

    public static byte[] repairPdf(Path pdfPath) throws IOException {
        try (PDDocument document = PdfDocuments.load(pdfPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            document.save(baos, CompressParameters.DEFAULT_COMPRESSION);
            return baos.toByteArray();
        }
    }

    public static byte[] flattenPdf(PDDocument document) throws IOException {
        PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
        if (acroForm != null) {
            acroForm.flatten();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        document.save(baos, CompressParameters.NO_COMPRESSION);
        return baos.toByteArray();
    }

    public static byte[] addBlankPages(PDDocument document, int[] positions, Float pageWidth, Float pageHeight) throws IOException {
        float bWidth = pageWidth != null ? pageWidth : PDRectangle.A4.getWidth();
        float bHeight = pageHeight != null ? pageHeight : PDRectangle.A4.getHeight();

        if (positions == null || positions.length == 0) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }

        int[] sorted = Arrays.stream(positions).sorted().toArray();
        // try-with-resources: newDoc was closed only after a successful save, so a failure
        // partway through leaked it.
        try (PDDocument newDoc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDPageTree oldPages = document.getPages();
            int totalOld = document.getNumberOfPages();
            int insertIndex = 0;

            for (int i = 0; i <= totalOld; i++) {
                while (insertIndex < sorted.length && sorted[insertIndex] == i) {
                    newDoc.addPage(new PDPage(new PDRectangle(bWidth, bHeight)));
                    insertIndex++;
                }
                if (i < totalOld) {
                    newDoc.addPage(oldPages.get(i));
                }
            }

            newDoc.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    public static byte[] stampPdf(Path sourcePath, Path stampPath, Float opacity, Integer fromPage, Integer toPage) throws IOException {
        try (PDDocument source = PdfDocuments.load(sourcePath);
             PDDocument stamp = PdfDocuments.load(stampPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            if (fromPage == null) fromPage = 0;
            if (toPage == null) toPage = source.getNumberOfPages() - 1;
            if (opacity == null) opacity = 1.0f;

            LayerUtility layerUtility = new LayerUtility(source);
            PDFormXObject stampForm = layerUtility.importPageAsForm(stamp, 0);

            for (int pNo = Math.max(0, fromPage); pNo <= toPage && pNo < source.getNumberOfPages(); pNo++) {
                PDPage page = source.getPage(pNo);
                try (PDPageContentStream cs = new PDPageContentStream(source, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    if (opacity < 1.0f) {
                        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                        gs.setNonStrokingAlphaConstant(opacity);
                        gs.setAlphaSourceFlag(true);
                        cs.setGraphicsStateParameters(gs);
                    }
                    cs.drawForm(stampForm);
                }
            }

            source.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    /**
     * Places an image at an exact position/size on a single PDF page.
     * x_frac, y_frac: top-left position as fractions of page dimensions (0.0–1.0).
     * widthFrac, heightFrac: image size as fractions of page dimensions.
     * PDFBox origin is bottom-left, so y is converted from top-left fraction.
     */
    public static byte[] placeImage(Path pdfPath, byte[] imageBytes,
                                    int pageIndex, float xFrac, float yFrac,
                                    float widthFrac, float heightFrac) throws Exception {
        try (PDDocument doc = PdfDocuments.load(pdfPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages())
                throw new IllegalArgumentException("Page index out of range: " + pageIndex);

            PDPage page = doc.getPage(pageIndex);
            PDRectangle mediaBox = page.getMediaBox();
            float pageWidth = mediaBox.getWidth();
            float pageHeight = mediaBox.getHeight();

            float x = xFrac * pageWidth;
            float w = widthFrac * pageWidth;
            float h = heightFrac * pageHeight;
            // Convert from top-left to bottom-left coordinate origin used by PDFBox
            float y = pageHeight - (yFrac * pageHeight) - h;

            PDImageXObject pdImage = PDImageXObject.createFromByteArray(doc, imageBytes, "overlay");

            try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.drawImage(pdImage, x, y, w, h);
            }

            doc.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    /**
     * Draws solid black rectangles over each specified region to permanently redact content.
     * Client sends top-left origin coords; PDFBox uses bottom-left, so Y is inverted.
     */
    public static byte[] redactPdf(Path pdfPath, List<RedactRegion> regions) throws IOException {
        try (PDDocument doc = PdfDocuments.load(pdfPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            for (RedactRegion region : regions) {
                if (region.getPage() < 0 || region.getPage() >= doc.getNumberOfPages()) continue;
                PDPage page = doc.getPage(region.getPage());
                float pageHeight = page.getMediaBox().getHeight();
                // Invert Y: PDFBox origin is bottom-left; client sends top-left origin
                float pdfY = pageHeight - region.getY() - region.getHeight();

                try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.setNonStrokingColor(Color.BLACK);
                    cs.addRect(region.getX(), pdfY, region.getWidth(), region.getHeight());
                    cs.fill();
                }
            }

            doc.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    /**
     * Duplicates selected pages by inserting {@code count} copies after each selected page.
     * Uses importPage() — required when copying pages across PDDocument instances.
     */
    public static byte[] duplicatePages(Path pdfPath, List<Integer> pageIndices, int count) throws IOException {
        try (PDDocument src = PdfDocuments.load(pdfPath);
             PDDocument out = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            int total = src.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                out.importPage(src.getPage(i));
                if (pageIndices.contains(i)) {
                    for (int c = 0; c < count; c++) {
                        out.importPage(src.getPage(i));
                    }
                }
            }

            out.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    /**
     * Duplicates pages with an independent copy count per page.
     * {@code pageCounts} maps a 0-indexed page number to the number of extra
     * copies to insert directly after it (0/absent = leave as-is).
     */
    public static byte[] duplicatePages(Path pdfPath, Map<Integer, Integer> pageCounts) throws IOException {
        try (PDDocument src = PdfDocuments.load(pdfPath);
             PDDocument out = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            int total = src.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                out.importPage(src.getPage(i));
                Integer copies = pageCounts.get(i);
                if (copies != null) {
                    for (int c = 0; c < copies; c++) {
                        out.importPage(src.getPage(i));
                    }
                }
            }

            out.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    /**
     * Strips identifying metadata from a PDF: the document information
     * dictionary (title, author, subject, keywords, creator, producer, dates)
     * and any XMP metadata stream.
     */
    public static byte[] removeMetadata(Path pdfPath) throws IOException {
        try (PDDocument doc = PdfDocuments.load(pdfPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            doc.setDocumentInformation(new PDDocumentInformation());
            doc.getDocumentCatalog().setMetadata(null);
            doc.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    /** Extracts embedded raster images from every page and returns them zipped as PNGs. */
    public static byte[] extractImages(Path pdfPath) throws IOException {
        try (PDDocument doc = PdfDocuments.load(pdfPath);
             ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(zipBaos)) {
            int count = 0;
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                if (res == null) continue;
                for (COSName name : res.getXObjectNames()) {
                    try {
                        PDXObject xobj = res.getXObject(name);
                        if (xobj instanceof PDImageXObject img) {
                            BufferedImage bi = img.getImage();
                            if (bi == null) continue;
                            count++;
                            ByteArrayOutputStream imgBaos = new ByteArrayOutputStream();
                            ImageIO.write(bi, "png", imgBaos);
                            zip.putNextEntry(new ZipEntry("image_" + count + ".png"));
                            zip.write(imgBaos.toByteArray());
                            zip.closeEntry();
                        }
                    } catch (Exception ignore) {
                        // Skip images PDFBox/ImageIO can't decode (e.g. exotic colour spaces).
                    }
                }
            }
            zip.finish();
            if (count == 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOTHING_TO_EXTRACT",
                    "This PDF contains no embedded images. Pages drawn with text or vector "
                            + "graphics have no images to pull out.");
            return zipBaos.toByteArray();
        }
    }

    /**
     * Removes potentially unsafe / tracking content: document-level JavaScript,
     * embedded files, open/additional actions, and all metadata — leaving the
     * visible page content intact.
     */
    public static byte[] sanitizePdf(Path pdfPath) throws IOException {
        try (PDDocument doc = PdfDocuments.load(pdfPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDDocumentCatalog cat = doc.getDocumentCatalog();
            cat.setOpenAction(null);
            COSDictionary catDict = cat.getCOSObject();
            // The /Names tree holds JavaScript and EmbeddedFiles; /AA holds
            // additional (event) actions. Dropping them removes active content.
            catDict.removeItem(COSName.getPDFName("Names"));
            catDict.removeItem(COSName.getPDFName("AA"));
            catDict.removeItem(COSName.getPDFName("OpenAction"));
            cat.setMetadata(null);
            doc.setDocumentInformation(new PDDocumentInformation());
            doc.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    /**
     * Splits a PDF into multiple parts, each at most {@code maxBytes} in size,
     * returned as a ZIP. Pages are measured individually and packed greedily; a
     * single page larger than the limit becomes its own part.
     */
    public static byte[] splitBySize(Path pdfPath, long maxBytes) throws IOException {
        try (PDDocument src = PdfDocuments.load(pdfPath)) {
            // Splitter returns independent single-page documents (safe clones).
            List<PDDocument> pages = new Splitter().split(src);
            List<byte[]> pageBytes = new ArrayList<>();
            for (PDDocument p : pages) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                p.save(b, CompressParameters.NO_COMPRESSION);
                pageBytes.add(b.toByteArray());
                p.close();
            }

            ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(zipBaos)) {
                int i = 0, part = 0;
                while (i < pageBytes.size()) {
                    List<byte[]> group = new ArrayList<>();
                    long acc = 0;
                    while (i < pageBytes.size() && (group.isEmpty() || acc + pageBytes.get(i).length <= maxBytes)) {
                        acc += pageBytes.get(i).length;
                        group.add(pageBytes.get(i));
                        i++;
                    }
                    PDFMergerUtility merger = new PDFMergerUtility();
                    ByteArrayOutputStream chunk = new ByteArrayOutputStream();
                    merger.setDestinationStream(chunk);
                    for (byte[] pb : group) merger.addSource(new RandomAccessReadBuffer(pb));
                    merger.mergeDocuments(null);
                    part++;
                    zip.putNextEntry(new ZipEntry("part_" + part + ".pdf"));
                    zip.write(chunk.toByteArray());
                    zip.closeEntry();
                }
            }
            return zipBaos.toByteArray();
        }
    }

    /**
     * Mirrors (flips) pages horizontally or vertically, preserving vector quality
     * by re-drawing each page as a form under a mirror matrix. {@code pageIndices}
     * empty = all pages; listed pages are flipped, the rest copied unchanged.
     */
    public static byte[] mirrorPdf(Path pdfPath, boolean horizontal, List<Integer> pageIndices) throws IOException {
        try (PDDocument src = PdfDocuments.load(pdfPath);
             PDDocument out = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            LayerUtility lu = new LayerUtility(out);
            boolean all = (pageIndices == null || pageIndices.isEmpty());
            int total = src.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDRectangle box = src.getPage(i).getMediaBox();
                PDFormXObject form = lu.importPageAsForm(src, i);
                PDPage outPage = new PDPage(new PDRectangle(box.getWidth(), box.getHeight()));
                out.addPage(outPage);
                try (PDPageContentStream cs = new PDPageContentStream(out, outPage)) {
                    if (all || pageIndices.contains(i)) {
                        Matrix m = horizontal
                                ? new Matrix(-1, 0, 0, 1, box.getWidth(), 0)
                                : new Matrix(1, 0, 0, -1, 0, box.getHeight());
                        cs.transform(m);
                    }
                    cs.drawForm(form);
                }
            }
            out.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    /** Resizes every page to a standard size, scaling content to fit and centering it. */
    public static byte[] resizePageSize(Path pdfPath, float targetW, float targetH) throws IOException {
        try (PDDocument src = PdfDocuments.load(pdfPath);
             PDDocument out = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            LayerUtility lu = new LayerUtility(out);
            int total = src.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDRectangle box = src.getPage(i).getMediaBox();
                PDFormXObject form = lu.importPageAsForm(src, i);
                PDPage outPage = new PDPage(new PDRectangle(targetW, targetH));
                out.addPage(outPage);
                float scale = Math.min(targetW / box.getWidth(), targetH / box.getHeight());
                float tx = (targetW - box.getWidth() * scale) / 2f;
                float ty = (targetH - box.getHeight() * scale) / 2f;
                try (PDPageContentStream cs = new PDPageContentStream(out, outPage)) {
                    cs.transform(new Matrix(scale, 0, 0, scale, tx, ty));
                    cs.drawForm(form);
                }
            }
            out.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    /** Scales page size and content uniformly by {@code factor}. */
    public static byte[] scalePdf(Path pdfPath, float factor) throws IOException {
        if (factor <= 0) factor = 1f;
        try (PDDocument src = PdfDocuments.load(pdfPath);
             PDDocument out = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            LayerUtility lu = new LayerUtility(out);
            int total = src.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDRectangle box = src.getPage(i).getMediaBox();
                PDFormXObject form = lu.importPageAsForm(src, i);
                PDPage outPage = new PDPage(new PDRectangle(box.getWidth() * factor, box.getHeight() * factor));
                out.addPage(outPage);
                try (PDPageContentStream cs = new PDPageContentStream(out, outPage)) {
                    cs.transform(new Matrix(factor, 0, 0, factor, 0, 0));
                    cs.drawForm(form);
                }
            }
            out.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    /**
     * Inserts {@code insertPath} into {@code basePath} after the 0-indexed page
     * {@code afterPage} (-1 = at the very start). Splices via single-page clones +
     * merge so no source document is mutated.
     */
    public static byte[] insertPdf(Path basePath, Path insertPath, int afterPage) throws IOException {
        List<byte[]> basePages = pagesToBytes(basePath);
        List<byte[]> insertPages = pagesToBytes(insertPath);
        int pos = Math.max(-1, Math.min(afterPage, basePages.size() - 1));

        List<byte[]> ordered = new ArrayList<>();
        for (int i = 0; i <= pos; i++) ordered.add(basePages.get(i));
        ordered.addAll(insertPages);
        for (int i = pos + 1; i < basePages.size(); i++) ordered.add(basePages.get(i));

        PDFMergerUtility merger = new PDFMergerUtility();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        merger.setDestinationStream(baos);
        for (byte[] pb : ordered) merger.addSource(new RandomAccessReadBuffer(pb));
        merger.mergeDocuments(null);
        return baos.toByteArray();
    }

    // Splits a document into independent single-page PDFs (as bytes).
    private static List<byte[]> pagesToBytes(Path pdfPath) throws IOException {
        try (PDDocument doc = PdfDocuments.load(pdfPath)) {
            List<byte[]> result = new ArrayList<>();
            for (PDDocument page : new Splitter().split(doc)) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                page.save(b, CompressParameters.NO_COMPRESSION);
                result.add(b.toByteArray());
                page.close();
            }
            return result;
        }
    }

    /** Extracts embedded/attached files into a ZIP; throws if the PDF has none. */
    public static byte[] extractEmbeddedFiles(Path pdfPath) throws IOException {
        try (PDDocument doc = PdfDocuments.load(pdfPath);
             ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(zipBaos)) {
            PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
            int[] count = {0};
            if (names != null && names.getEmbeddedFiles() != null) {
                writeEmbeddedNode(names.getEmbeddedFiles(), zip, count);
            }
            zip.finish();
            if (count[0] == 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOTHING_TO_EXTRACT",
                    "This PDF has no file attachments.");
            return zipBaos.toByteArray();
        }
    }

    private static void writeEmbeddedNode(PDNameTreeNode<PDComplexFileSpecification> node, ZipOutputStream zip, int[] count) throws IOException {
        Map<String, PDComplexFileSpecification> map = node.getNames();
        if (map != null) {
            for (Map.Entry<String, PDComplexFileSpecification> e : map.entrySet()) {
                PDComplexFileSpecification spec = e.getValue();
                PDEmbeddedFile ef = spec != null ? spec.getEmbeddedFile() : null;
                if (ef == null) continue;
                String filename = spec.getFilename();
                if (filename == null || filename.isBlank()) filename = e.getKey();
                count[0]++;
                zip.putNextEntry(new ZipEntry(filename));
                zip.write(ef.toByteArray());
                zip.closeEntry();
            }
        }
        if (node.getKids() != null) {
            for (PDNameTreeNode<PDComplexFileSpecification> kid : node.getKids()) {
                writeEmbeddedNode(kid, zip, count);
            }
        }
    }

    /**
     * Produces a read-only analysis report: page/word/character counts, blank,
     * duplicate and landscape page lists, embedded image/font/attachment counts
     * and file size. Returns a plain map (serialised as JSON).
     */
    public static Map<String, Object> analyzePdf(Path pdfPath) throws IOException {
        try (PDDocument doc = PdfDocuments.load(pdfPath)) {
            Map<String, Object> r = new LinkedHashMap<>();
            int total = doc.getNumberOfPages();
            r.put("pageCount", total);
            r.put("fileSizeBytes", Files.size(pdfPath));

            PDFRenderer renderer = new PDFRenderer(doc);
            PDFTextStripper stripper = new PDFTextStripper();
            int chars = 0, words = 0;
            List<Integer> landscape = new ArrayList<>();
            List<Integer> blank = new ArrayList<>();
            Map<String, List<Integer>> hashes = new LinkedHashMap<>();

            for (int i = 0; i < total; i++) {
                PDRectangle box = doc.getPage(i).getMediaBox();
                if (box.getWidth() > box.getHeight()) landscape.add(i + 1);

                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String pageText = stripper.getText(doc);
                chars += pageText.length();
                String trimmed = pageText.trim();
                if (!trimmed.isEmpty()) words += trimmed.split("\\s+").length;

                try {
                    BufferedImage img = renderer.renderImageWithDPI(i, 72, ImageType.GRAY);
                    if (isBlankPage(img, 0.99f)) blank.add(i + 1);
                } catch (Exception ignore) { /* skip un-renderable page */ }

                long contentLen = 0;
                try (var cs = doc.getPage(i).getContents()) {
                    if (cs != null) contentLen = cs.readAllBytes().length;
                } catch (Exception ignore) { /* no content */ }
                String h = pageText.hashCode() + "_" + contentLen;
                hashes.computeIfAbsent(h, k -> new ArrayList<>()).add(i + 1);
            }
            r.put("wordCount", words);
            r.put("characterCount", chars);
            r.put("landscapePages", landscape);
            r.put("blankPages", blank);

            List<List<Integer>> dupGroups = new ArrayList<>();
            for (List<Integer> g : hashes.values()) if (g.size() > 1) dupGroups.add(g);
            r.put("duplicatePageGroups", dupGroups);

            int imageCount = 0, fontCount = 0;
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                if (res == null) continue;
                for (COSName n : res.getXObjectNames()) {
                    try {
                        if (res.getXObject(n) instanceof PDImageXObject) imageCount++;
                    } catch (Exception ignore) {}
                }
                for (COSName ignored : res.getFontNames()) fontCount++;
            }
            r.put("imageCount", imageCount);
            r.put("fontCount", fontCount);

            int attachments = 0;
            PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
            if (names != null && names.getEmbeddedFiles() != null && names.getEmbeddedFiles().getNames() != null) {
                attachments = names.getEmbeddedFiles().getNames().size();
            }
            r.put("attachmentCount", attachments);
            r.put("encrypted", doc.isEncrypted());
            return r;
        }
    }

    /**
     * Replaces base pages {@code from}..{@code to} (1-indexed, inclusive) with all
     * pages of {@code replPath}. Splices via single-page clones + merge.
     */
    public static byte[] replacePages(Path basePath, Path replPath, int from, int to) throws IOException {
        List<byte[]> basePages = pagesToBytes(basePath);
        List<byte[]> replPages = pagesToBytes(replPath);
        int n = basePages.size();
        int f = Math.max(1, Math.min(from, n));
        int t = Math.max(f, Math.min(to, n));

        List<byte[]> ordered = new ArrayList<>();
        for (int i = 0; i < f - 1; i++) ordered.add(basePages.get(i)); // pages before the range
        ordered.addAll(replPages);                                     // the replacement
        for (int i = t; i < n; i++) ordered.add(basePages.get(i));     // pages after the range

        PDFMergerUtility merger = new PDFMergerUtility();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        merger.setDestinationStream(baos);
        for (byte[] pb : ordered) merger.addSource(new RandomAccessReadBuffer(pb));
        merger.mergeDocuments(null);
        return baos.toByteArray();
    }

    /** Extracts embedded font programs (page + AcroForm resources) into a ZIP. */
    public static byte[] extractFonts(Path pdfPath) throws IOException {
        try (PDDocument doc = PdfDocuments.load(pdfPath);
             ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(zipBaos)) {
            int[] count = {0};
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (PDPage page : doc.getPages()) extractFontsFromResources(page.getResources(), zip, count, seen);
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form != null) extractFontsFromResources(form.getDefaultResources(), zip, count, seen);
            zip.finish();
            if (count[0] == 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOTHING_TO_EXTRACT",
                    "This PDF has no embedded fonts. It may rely on fonts installed on the "
                            + "reader's system instead.");
            return zipBaos.toByteArray();
        }
    }

    private static void extractFontsFromResources(PDResources res, ZipOutputStream zip, int[] count, java.util.Set<String> seen) {
        if (res == null) return;
        for (COSName name : res.getFontNames()) {
            try {
                PDFont font = res.getFont(name);
                if (font == null) continue;
                PDFontDescriptor fd = font.getFontDescriptor();
                if (fd == null) continue;
                PDStream ff = null;
                String ext = "";
                if (fd.getFontFile2() != null) { ff = fd.getFontFile2(); ext = ".ttf"; }
                else if (fd.getFontFile3() != null) { ff = fd.getFontFile3(); ext = ".otf"; }
                else if (fd.getFontFile() != null) { ff = fd.getFontFile(); ext = ".pfb"; }
                if (ff == null) continue;
                String base = font.getName() != null ? font.getName() : ("font_" + (count[0] + 1));
                base = base.replaceAll("[^a-zA-Z0-9._-]", "_");
                if (!seen.add(base + ext)) continue; // de-dupe repeated fonts
                count[0]++;
                zip.putNextEntry(new ZipEntry(base + ext));
                zip.write(ff.toByteArray());
                zip.closeEntry();
            } catch (Exception ignore) {
                // Skip fonts that can't be read.
            }
        }
    }

    /**
     * Turns a PDF into a fillable form by adding real interactive AcroForm
     * fields. Supported types: text, multiline, date (a text field), checkbox,
     * dropdown, radio (grouped by field name) and signature. Coordinates arrive
     * top-left origin in PDF points and are flipped to PDFBox's bottom-left.
     *
     * NeedAppearances is enabled so readers generate field appearances; some
     * mobile viewers render these more faithfully than others.
     */
    public static byte[] createForm(Path pdfPath, List<FormFieldSpec> specs) throws IOException {
        try (PDDocument doc = PdfDocuments.load(pdfPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            PDAcroForm acro = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acro);

            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDResources dr = new PDResources();
            dr.put(COSName.getPDFName("Helv"), font);
            acro.setDefaultResources(dr);
            acro.setDefaultAppearance("/Helv 0 Tf 0 g");
            acro.setNeedAppearances(true);

            if (specs == null) specs = new ArrayList<>();

            // Radio options share one field, grouped by name; everything else is standalone.
            Map<String, List<FormFieldSpec>> radioGroups = new LinkedHashMap<>();
            List<FormFieldSpec> singles = new ArrayList<>();
            for (FormFieldSpec f : specs) {
                if ("radio".equalsIgnoreCase(f.getType())) {
                    radioGroups.computeIfAbsent(safeName(f.getName(), "radio"), k -> new ArrayList<>()).add(f);
                } else {
                    singles.add(f);
                }
            }

            int auto = 0;
            for (FormFieldSpec f : singles) {
                String type = f.getType() == null ? "text" : f.getType().toLowerCase();
                String name = safeName(f.getName(), type + "_" + (auto++));
                PDPage page = doc.getPage(clampPage(f.getPage(), doc));
                PDRectangle rect = toRect(f, page);

                switch (type) {
                    case "checkbox": {
                        PDCheckBox cb = new PDCheckBox(acro);
                        cb.setPartialName(name);
                        acro.getFields().add(cb);
                        placeWidget(cb, page, rect);
                        if (Boolean.TRUE.equals(f.getRequired())) cb.setRequired(true);
                        boolean on = Boolean.TRUE.equals(f.getChecked()) || isTruthy(f.getValue());
                        // Build real On/Off appearance streams so the box renders and toggles.
                        buildToggleAppearance(doc, cb.getWidgets().get(0), "Yes", false, on);
                        cb.getCOSObject().setName(COSName.V, on ? "Yes" : "Off");
                        cb.getCOSObject().setName(COSName.getPDFName("DV"), on ? "Yes" : "Off");
                        break;
                    }
                    case "dropdown": {
                        PDComboBox combo = new PDComboBox(acro);
                        combo.setPartialName(name);
                        acro.getFields().add(combo);
                        if (f.getOptions() != null && !f.getOptions().isEmpty()) combo.setOptions(f.getOptions());
                        placeWidget(combo, page, rect);
                        if (f.getValue() != null && !f.getValue().isBlank()) {
                            try { combo.setValue(f.getValue()); } catch (Exception ignore) {}
                        }
                        break;
                    }
                    case "signature": {
                        PDSignatureField sig = new PDSignatureField(acro);
                        sig.setPartialName(name);
                        acro.getFields().add(sig);
                        placeWidget(sig, page, rect);
                        break;
                    }
                    default: { // text, multiline, date
                        PDTextField tf = new PDTextField(acro);
                        tf.setPartialName(name);
                        acro.getFields().add(tf);
                        if ("multiline".equals(type)) tf.setMultiline(true);
                        float fs = f.getFontSize() == null ? 0f : f.getFontSize();
                        tf.setDefaultAppearance("/Helv " + fs + " Tf 0 g");
                        placeWidget(tf, page, rect);
                        if (Boolean.TRUE.equals(f.getRequired())) tf.setRequired(true);
                        if (f.getValue() != null && !f.getValue().isBlank()) {
                            try { tf.setValue(f.getValue()); } catch (Exception ignore) {}
                        }
                    }
                }
            }

            for (Map.Entry<String, List<FormFieldSpec>> e : radioGroups.entrySet()) {
                PDRadioButton radio = new PDRadioButton(acro);
                radio.setPartialName(e.getKey());
                acro.getFields().add(radio);

                List<PDAnnotationWidget> widgets = new ArrayList<>();
                List<String> exports = new ArrayList<>();
                String selected = "Off";
                int idx = 0;
                for (FormFieldSpec f : e.getValue()) {
                    PDPage page = doc.getPage(clampPage(f.getPage(), doc));
                    // Export value doubles as the widget's on-state name (COSName-safe).
                    String onState = safeName(f.getExportValue(), "opt" + idx).replaceAll("[^A-Za-z0-9_]", "_");
                    boolean on = Boolean.TRUE.equals(f.getChecked());
                    if (on) selected = onState;

                    PDAnnotationWidget w = new PDAnnotationWidget();
                    w.setRectangle(toRect(f, page));
                    w.setPage(page);
                    w.setPrinted(true);
                    w.getCOSObject().setItem(COSName.PARENT, radio.getCOSObject());
                    page.getAnnotations().add(w);
                    buildToggleAppearance(doc, w, onState, true, on);

                    widgets.add(w);
                    exports.add(onState);
                    idx++;
                }
                radio.setWidgets(widgets);
                try { radio.setExportValues(exports); } catch (Exception ignore) {}
                radio.getCOSObject().setName(COSName.V, selected);
            }

            // Text and choice fields can be auto-generated; buttons we built above.
            try { acro.refreshAppearances(); } catch (Exception ignore) {}

            doc.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    /**
     * Builds On + Off normal appearance streams for a checkbox/radio widget so it
     * renders and toggles in any viewer (not just ones that honour NeedAppearances).
     * ZapfDingbats "4" = check, "l" = filled circle.
     */
    private static void buildToggleAppearance(PDDocument doc, PDAnnotationWidget widget, String onState, boolean radio, boolean on) throws IOException {
        PDRectangle r = widget.getRectangle();
        float w = r.getWidth(), h = r.getHeight();
        PDFont zapf = new PDType1Font(Standard14Fonts.FontName.ZAPF_DINGBATS);
        float fontSize = Math.max(4f, Math.min(w, h) * 0.8f);
        String glyph = radio ? "l" : "4";

        // Visible border/box.
        PDBorderStyleDictionary bs = new PDBorderStyleDictionary();
        bs.setWidth(1);
        bs.setStyle(PDBorderStyleDictionary.STYLE_SOLID);
        widget.setBorderStyle(bs);
        PDAppearanceCharacteristicsDictionary mk = new PDAppearanceCharacteristicsDictionary(new COSDictionary());
        widget.getCOSObject().setItem(COSName.MK, mk.getCOSObject());

        PDAppearanceStream onAp = buildToggleStream(doc, w, h, radio, glyph, zapf, fontSize, true);
        PDAppearanceStream offAp = buildToggleStream(doc, w, h, radio, glyph, zapf, fontSize, false);

        PDAppearanceDictionary ap = new PDAppearanceDictionary();
        COSDictionary normal = new COSDictionary();
        normal.setItem(COSName.getPDFName(onState), onAp.getCOSObject());
        normal.setItem(COSName.Off, offAp.getCOSObject());
        ap.getCOSObject().setItem(COSName.N, normal);
        widget.setAppearance(ap);
        widget.getCOSObject().setName(COSName.AS, on ? onState : "Off");
    }

    private static PDAppearanceStream buildToggleStream(PDDocument doc, float w, float h, boolean radio, String glyph, PDFont zapf, float fontSize, boolean drawGlyph) throws IOException {
        PDAppearanceStream ap = new PDAppearanceStream(doc);
        ap.setResources(new PDResources());
        ap.setBBox(new PDRectangle(w, h));
        try (PDPageContentStream cs = new PDPageContentStream(doc, ap)) {
            cs.setLineWidth(1f);
            if (radio) {
                addCircle(cs, w / 2f, h / 2f, Math.min(w, h) / 2f - 0.75f);
                cs.stroke();
            } else {
                cs.addRect(0.75f, 0.75f, w - 1.5f, h - 1.5f);
                cs.stroke();
            }
            if (drawGlyph) {
                float tw = zapf.getStringWidth(glyph) / 1000f * fontSize;
                cs.beginText();
                cs.setFont(zapf, fontSize);
                cs.newLineAtOffset((w - tw) / 2f, (h - fontSize) / 2f + fontSize * 0.18f);
                cs.showText(glyph);
                cs.endText();
            }
        }
        return ap;
    }

    // Approximates a circle with four bezier curves.
    private static void addCircle(PDPageContentStream cs, float cx, float cy, float rad) throws IOException {
        float k = 0.5523f * rad;
        cs.moveTo(cx - rad, cy);
        cs.curveTo(cx - rad, cy + k, cx - k, cy + rad, cx, cy + rad);
        cs.curveTo(cx + k, cy + rad, cx + rad, cy + k, cx + rad, cy);
        cs.curveTo(cx + rad, cy - k, cx + k, cy - rad, cx, cy - rad);
        cs.curveTo(cx - k, cy - rad, cx - rad, cy - k, cx - rad, cy);
        cs.closePath();
    }

    /** Lists a PDF's existing (terminal) AcroForm fields as JSON-friendly maps. */
    public static List<Map<String, Object>> getFormFields(PDDocument doc) {
        List<Map<String, Object>> result = new ArrayList<>();
        PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
        if (form == null) return result;
        for (PDField field : form.getFieldTree()) {
            if (!(field instanceof org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", field.getFullyQualifiedName());
            String type;
            List<String> options = null;
            if (field instanceof PDCheckBox) {
                type = "checkbox";
            } else if (field instanceof PDRadioButton rb) {
                type = "radio";
                options = new ArrayList<>(rb.getExportValues());
            } else if (field instanceof PDComboBox cb) {
                type = "dropdown";
                options = cb.getOptions();
            } else if (field instanceof PDListBox lb) {
                type = "dropdown";
                options = lb.getOptions();
            } else if (field instanceof PDSignatureField) {
                type = "signature";
            } else {
                type = "text";
            }
            m.put("type", type);
            if (options != null) m.put("options", options);
            try { m.put("value", field.getValueAsString()); } catch (Exception ignore) { m.put("value", ""); }
            result.add(m);
        }
        return result;
    }

    /** Fills the given field values (by fully-qualified name), then flattens the form. */
    public static byte[] fillFlatten(Path pdfPath, Map<String, String> values) throws IOException {
        try (PDDocument doc = PdfDocuments.load(pdfPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form != null) {
                form.setNeedAppearances(false);
                if (values != null) {
                    for (Map.Entry<String, String> e : values.entrySet()) {
                        PDField field = form.getField(e.getKey());
                        if (field == null || e.getValue() == null) continue;
                        try { field.setValue(e.getValue()); } catch (Exception ignore) { /* incompatible value */ }
                    }
                }
                try { form.refreshAppearances(); } catch (Exception ignore) {}
                form.flatten();
            }
            doc.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    private static String safeName(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    private static boolean isTruthy(String v) {
        return v != null && (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("yes") || v.equals("1"));
    }

    private static int clampPage(int p, PDDocument doc) {
        return Math.max(0, Math.min(p, doc.getNumberOfPages() - 1));
    }

    // Converts a top-left-origin rect (PDF points) to a PDFBox bottom-left rect.
    private static PDRectangle toRect(FormFieldSpec f, PDPage page) {
        float ph = page.getMediaBox().getHeight();
        float pdfY = ph - f.getY() - f.getHeight();
        return new PDRectangle(f.getX(), pdfY, f.getWidth(), f.getHeight());
    }

    // Positions a single-widget terminal field's widget on a page.
    private static void placeWidget(PDField field, PDPage page, PDRectangle rect) throws IOException {
        PDAnnotationWidget w = field.getWidgets().get(0);
        w.setRectangle(rect);
        w.setPage(page);
        w.setPrinted(true);
        page.getAnnotations().add(w);
    }

    /**
     * Reads the document outline (bookmarks) and returns a flat list of bookmark maps
     * with keys: title, pageIndex, children (recursive).
     */
    public static List<Map<String, Object>> getBookmarks(PDDocument doc) throws IOException {
        PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
        if (outline == null) return List.of();
        return collectOutlineItems(outline.getFirstChild(), doc);
    }

    private static List<Map<String, Object>> collectOutlineItems(PDOutlineItem item, PDDocument doc) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        while (item != null) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("title", item.getTitle() != null ? item.getTitle() : "");
            PDPage page = item.findDestinationPage(doc);
            entry.put("pageIndex", page != null ? doc.getPages().indexOf(page) : 0);
            entry.put("children", collectOutlineItems(item.getFirstChild(), doc));
            result.add(entry);
            item = item.getNextSibling();
        }
        return result;
    }

    /**
     * Replaces the document outline with bookmarks parsed from {@code bookmarksJson}.
     * Expected JSON: List<{title, pageIndex, children[]}>.
     * Uses a local ObjectMapper since this is a static utility method.
     */
    @SuppressWarnings("unchecked")
    public static byte[] editBookmarks(PDDocument doc, String bookmarksJson) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> bookmarkList = mapper.readValue(bookmarksJson, List.class);

        PDDocumentOutline outline = new PDDocumentOutline();
        doc.getDocumentCatalog().setDocumentOutline(outline);
        buildOutlineItems(outline, bookmarkList, doc, mapper);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos, CompressParameters.NO_COMPRESSION);
        return baos.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void buildOutlineItems(PDOutlineNode parent, List<Map<String, Object>> items, PDDocument doc, ObjectMapper mapper) {
        for (Map<String, Object> item : items) {
            PDOutlineItem outlineItem = new PDOutlineItem();
            outlineItem.setTitle((String) item.getOrDefault("title", ""));

            Object pageIdxObj = item.get("pageIndex");
            int pageIndex = pageIdxObj instanceof Number num ? num.intValue() : 0;
            if (pageIndex >= 0 && pageIndex < doc.getNumberOfPages()) {
                PDPageFitWidthDestination dest = new PDPageFitWidthDestination();
                dest.setPage(doc.getPage(pageIndex));
                outlineItem.setDestination(dest);
            }

            parent.addLast(outlineItem);

            Object childrenObj = item.get("children");
            if (childrenObj instanceof List<?> children && !children.isEmpty()) {
                buildOutlineItems(outlineItem, (List<Map<String, Object>>) children, doc, mapper);
            }
        }
    }

    /**
     * Removes pages where >= threshold fraction of pixels are near-white (>= 240 grayscale).
     * Renders at 72 DPI for speed. Returns original bytes if all or no pages would be removed.
     */
    /**
     * Result of a blank-page removal, so the caller can distinguish "removed nothing"
     * from "removed several" — returning only the bytes made a no-op look like a success.
     */
    public record BlankPageResult(byte[] document, int removed) {}

    /** @return the cleaned document plus how many pages were dropped. */
    public static BlankPageResult removeBlankPagesDetailed(Path pdfPath, float threshold) throws IOException {
        try (PDDocument src = PdfDocuments.load(pdfPath)) {
            PDFRenderer renderer = new PDFRenderer(src);
            List<Integer> keepPages = new ArrayList<>();
            for (int i = 0; i < src.getNumberOfPages(); i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, 72, ImageType.GRAY);
                if (!isBlankPage(img, threshold)) keepPages.add(i);
            }
            final int removed = src.getNumberOfPages() - keepPages.size();
            if (keepPages.isEmpty() || removed == 0) {
                return new BlankPageResult(Files.readAllBytes(pdfPath), 0);
            }
            try (PDDocument out = new PDDocument();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                for (int idx : keepPages) out.importPage(src.getPage(idx));
                out.save(baos, CompressParameters.NO_COMPRESSION);
                return new BlankPageResult(baos.toByteArray(), removed);
            }
        }
    }

    public static byte[] removeBlankPages(Path pdfPath, float threshold) throws IOException {
        try (PDDocument src = PdfDocuments.load(pdfPath)) {
            PDFRenderer renderer = new PDFRenderer(src);
            List<Integer> keepPages = new ArrayList<>();
            for (int i = 0; i < src.getNumberOfPages(); i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, 72, ImageType.GRAY);
                if (!isBlankPage(img, threshold)) keepPages.add(i);
            }
            // Nothing to remove — hand back the original bytes unchanged.
            if (keepPages.isEmpty() || keepPages.size() == src.getNumberOfPages()) {
                return Files.readAllBytes(pdfPath);
            }
            try (PDDocument out = new PDDocument();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                for (int idx : keepPages) out.importPage(src.getPage(idx));
                out.save(baos, CompressParameters.NO_COMPRESSION);
                return baos.toByteArray();
            }
        }
    }

    private static boolean isBlankPage(BufferedImage img, float threshold) {
        int total = img.getWidth() * img.getHeight();
        if (total == 0) return true;
        int whiteCount = 0;
        int[] pixels = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        for (int px : pixels) {
            if (((px >> 16) & 0xFF) >= 240) whiteCount++;
        }
        return (float) whiteCount / total >= threshold;
    }

    /**
     * Removes embedded page thumbnails and re-saves the document to reduce file size.
     */
    public static byte[] optimizePdf(Path pdfPath) throws IOException {
        try (PDDocument doc = PdfDocuments.load(pdfPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            for (PDPage page : doc.getPages()) {
                page.getCOSObject().removeItem(COSName.THUMB);
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /**
     * Tiles nUp (2 or 4) input pages onto each output sheet.
     * 2-up: landscape A4, side by side. 4-up: portrait A4, 2×2 grid.
     *
     * Places each input page as a **vector form** (LayerUtility.importPageAsForm)
     * rather than a rasterised image, so text stays selectable/searchable and the
     * output stays crisp and small. Aspect ratio is preserved within each cell.
     */
    public static byte[] nUpPdf(Path pdfPath, int nUp) throws IOException {
        if (nUp != 2 && nUp != 4) nUp = 2;
        try (PDDocument src = PdfDocuments.load(pdfPath);
             PDDocument out = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            LayerUtility lu = new LayerUtility(out);
            int total = src.getNumberOfPages();
            int cols = 2;
            int rows = nUp == 4 ? 2 : 1;
            float outW = nUp == 4 ? 595f : 842f;
            float outH = nUp == 4 ? 842f : 595f;
            float cellW = outW / cols;
            float cellH = outH / rows;

            for (int i = 0; i < total; i += nUp) {
                PDPage outPage = new PDPage(new PDRectangle(outW, outH));
                out.addPage(outPage);
                try (PDPageContentStream cs = new PDPageContentStream(out, outPage)) {
                    for (int j = 0; j < nUp && i + j < total; j++) {
                        PDRectangle box = src.getPage(i + j).getCropBox();
                        PDFormXObject form = lu.importPageAsForm(src, i + j);
                        int col = j % cols;
                        int row = j / cols;
                        float x = col * cellW;
                        float y = outH - (row + 1) * cellH;
                        float sw = box.getWidth(), sh = box.getHeight();
                        float scale = Math.min(cellW / sw, cellH / sh);
                        float drawW = sw * scale, drawH = sh * scale;
                        float tx = x + (cellW - drawW) / 2f;
                        float ty = y + (cellH - drawH) / 2f;
                        cs.saveGraphicsState();
                        // Scale the page-form into the cell, offsetting for the crop-box origin.
                        cs.transform(new Matrix(scale, 0, 0, scale,
                                tx - box.getLowerLeftX() * scale,
                                ty - box.getLowerLeftY() * scale));
                        cs.drawForm(form);
                        cs.restoreGraphicsState();
                    }
                }
            }
            out.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    public static byte[] mergePdf(String outputFileName, List<MultipartFile> files) throws Exception {
        PDFMergerUtility merger = new PDFMergerUtility();
        merger.setDestinationFileName(outputFileName);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        merger.setDestinationStream(outputStream);
        merger.setDocumentMergeMode(PDFMergerUtility.DocumentMergeMode.OPTIMIZE_RESOURCES_MODE);
        merger.setAcroFormMergeMode(PDFMergerUtility.AcroFormMergeMode.JOIN_FORM_FIELDS_MODE);

        for (MultipartFile file : files) {
            final File tempFile = File.createTempFile(file.getName(), ".pdf");
            file.transferTo(tempFile);
            merger.addSource(tempFile);
        }
        merger.mergeDocuments(null);

        final byte[] bytes = outputStream.toByteArray();
        outputStream.close();
        return bytes;
    }

}

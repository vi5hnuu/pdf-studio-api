package com.vishnu.pdf_studio_api.pdfstudioapi.utils;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.ColorModel;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.RangeModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.FormFieldDef;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
public class PdfTools {
    public static byte[] compressPdf(byte[] fileBytes, CompressionLevel level) throws IOException {
        try (PDDocument document = Loader.loadPDF(fileBytes);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            for (int i = 0; i < document.getNumberOfPages(); i++) {
                compressImagesOnPage(document.getPage(i), document, level);
            }

            document.save(baos, CompressParameters.DEFAULT_COMPRESSION);
            return baos.toByteArray();
        }
    }

    public static byte[] watermarkPdf(PDDocument document, String text, int fontSize, ColorModel color, float opacity, double angleDegrees, Postion vPos, Postion hPos, Integer fromPage, Integer toPage) throws IOException {
        if (fromPage == null) fromPage = 0;
        if (toPage == null) toPage = document.getNumberOfPages() - 1;
        if (vPos == null) vPos = Postion.CENTER;
        if (hPos == null) hPos = Postion.CENTER;

        PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

        for (int pNo = fromPage; pNo <= toPage && pNo < document.getNumberOfPages(); pNo++) {
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

    public static byte[] grayscalePdf(byte[] fileBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(fileBytes);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            for (int i = 0; i < document.getNumberOfPages(); i++) {
                grayscaleImagesOnPage(document.getPage(i), document);
            }

            document.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }

    private static void grayscaleImagesOnPage(PDPage page, PDDocument document) throws IOException {
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

            BufferedImage gray = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = gray.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();

            PDImageXObject grayXObject = LosslessFactory.createFromImage(document, gray);
            xobjectDict.setItem(name, grayXObject.getCOSObject());
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
        PDDocument document = new PDDocument();

        for (MultipartFile file : files) {
            BufferedImage bimg = ImageIO.read(file.getInputStream());
            float width = bimg.getWidth();
            float height = bimg.getHeight();

            PDPage page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);

            PDImageXObject img = PDImageXObject.createFromByteArray(document, file.getBytes(), file.getName());
            PDPageContentStream contentStream = new PDPageContentStream(document, page);
            contentStream.drawImage(img, 0, 0);
            contentStream.close();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        document.save(byteArrayOutputStream, CompressParameters.NO_COMPRESSION);
        document.close();
        final byte[] bytes = byteArrayOutputStream.toByteArray();
        byteArrayOutputStream.close();
        return bytes;
    }

    public static byte[] reorderPdf(MultipartFile file, int[] order) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            final var loadedDoc = Loader.loadPDF(file.getBytes());
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
                    final PDDocument docX = new PDDocument();
                    for (int pNo = (docNo - 1) * fixed; pNo < document.getNumberOfPages() && pNo < docNo * fixed; pNo++) {
                        docX.addPage(pt.get(pNo));
                    }
                    ZipEntry entry = new ZipEntry("range_" + (docNo) + ".pdf");
                    zip.putNextEntry(entry);
                    docX.save(baos);
                    docX.close();
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

    public static byte[] writePageNumbersToPages(PDDocument document, Postion vPos, Postion hPos, Integer fromPage, Integer toPage, PageNoType pageNoType, ColorModel fillColor, Padding padding, Integer size, Standard14Fonts.FontName fontName) throws IOException {
        final float defaultMargin=3.0f;
        final String toWrite = pageNoType.getType().replace("Y", String.valueOf(document.getNumberOfPages())).replace("_", " ");
        PDFont font = new PDType1Font(fontName); // You can change the font as needed

        for (int pNo = fromPage; pNo <= toPage; pNo++) {
            final String text = toWrite.replace("X", String.valueOf(pNo + 1));
            float textWidth = font.getStringWidth(text) / 1000 * size;
            float textHeight = font.getFontDescriptor().getFontBoundingBox().getHeight() / 1000 * size;

            PDPage page = document.getPage(pNo);


            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();

            float xCoord = switch (hPos) {
                case Postion.START -> padding.getLeft() + defaultMargin;
                case Postion.CENTER -> Math.max(0, pageWidth / 2 - textWidth / 2.0f);
                case Postion.END -> Math.max(0, pageWidth - textWidth - padding.getRight()-defaultMargin);
            };

            float yCoord = switch (vPos) {
                case Postion.START -> pageHeight - textHeight - padding.getTop()-defaultMargin;
                case Postion.CENTER -> pageHeight / 2-textHeight/2.0f;
                case Postion.END -> padding.getBottom()+defaultMargin;
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

        for (int pNo = fromPage; pNo <= toPage && pNo < document.getNumberOfPages(); pNo++) {
            PDPage page = document.getPage(pNo);
            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();

            try (PDPageContentStream cs = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.setNonStrokingColor(color.color());
                cs.setFont(font, fontSize);

                if (headerText != null && !headerText.isBlank()) {
                    float textWidth = font.getStringWidth(headerText) / 1000f * fontSize;
                    float x = (pageWidth - textWidth) / 2f;
                    float y = pageHeight - topPadding - fontSize;
                    cs.beginText();
                    cs.newLineAtOffset(x, y);
                    cs.showText(headerText);
                    cs.endText();
                }

                if (footerText != null && !footerText.isBlank()) {
                    float textWidth = font.getStringWidth(footerText) / 1000f * fontSize;
                    float x = (pageWidth - textWidth) / 2f;
                    float y = bottomPadding;
                    cs.beginText();
                    cs.newLineAtOffset(x, y);
                    cs.showText(footerText);
                    cs.endText();
                }
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        document.save(baos, CompressParameters.NO_COMPRESSION);
        return baos.toByteArray();
    }

    public static byte[] repairPdf(byte[] fileBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(fileBytes);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            document.save(baos, CompressParameters.DEFAULT_COMPRESSION);
            return baos.toByteArray();
        }
    }

    public static byte[] addFormFields(PDDocument document, List<FormFieldDef> fields) throws IOException {
        if (fields == null || fields.isEmpty()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }

        PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        PDAcroForm acroForm = catalog.getAcroForm();
        if (acroForm == null) {
            acroForm = new PDAcroForm(document);
            catalog.setAcroForm(acroForm);
        }
        acroForm.setNeedAppearances(true);

        PDResources dr = acroForm.getDefaultResources();
        if (dr == null) dr = new PDResources();
        dr.put(COSName.getPDFName("Helv"), font);
        acroForm.setDefaultResources(dr);

        for (FormFieldDef f : fields) {
            if (f.getPage() < 0 || f.getPage() >= document.getNumberOfPages()) continue;
            PDPage page = document.getPage(f.getPage());

            PDAnnotationWidget widget = new PDAnnotationWidget();
            widget.setRectangle(new PDRectangle(f.getX(), f.getY(), f.getWidth(), f.getHeight()));
            widget.setPage(page);
            widget.setPrinted(true);

            if (f.getType() == FormFieldDef.FieldType.CHECKBOX) {
                PDCheckBox field = new PDCheckBox(acroForm);
                field.setPartialName(f.getName() != null ? f.getName() : "checkbox_" + f.getPage() + "_" + (int) f.getX());
                field.getWidgets().add(widget);
                widget.setParent(field);
                acroForm.getFields().add(field);
            } else {
                PDTextField field = new PDTextField(acroForm);
                field.setPartialName(f.getName() != null ? f.getName() : "text_" + f.getPage() + "_" + (int) f.getX());
                field.setDefaultAppearance("/Helv 12 Tf 0 g");
                if (f.isMultiline()) field.setMultiline(true);
                if (f.getDefaultValue() != null) field.setValue(f.getDefaultValue());
                field.getWidgets().add(widget);
                widget.setParent(field);
                acroForm.getFields().add(field);
            }
            page.getAnnotations().add(widget);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        document.save(baos, CompressParameters.NO_COMPRESSION);
        return baos.toByteArray();
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
        PDDocument newDoc = new PDDocument();
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

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        newDoc.save(baos, CompressParameters.NO_COMPRESSION);
        newDoc.close();
        return baos.toByteArray();
    }

    public static byte[] stampPdf(byte[] sourceBytes, byte[] stampBytes, Float opacity, Integer fromPage, Integer toPage) throws IOException {
        try (PDDocument source = Loader.loadPDF(sourceBytes);
             PDDocument stamp = Loader.loadPDF(stampBytes);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            if (fromPage == null) fromPage = 0;
            if (toPage == null) toPage = source.getNumberOfPages() - 1;
            if (opacity == null) opacity = 1.0f;

            LayerUtility layerUtility = new LayerUtility(source);
            PDFormXObject stampForm = layerUtility.importPageAsForm(stamp, 0);

            for (int pNo = fromPage; pNo <= toPage && pNo < source.getNumberOfPages(); pNo++) {
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

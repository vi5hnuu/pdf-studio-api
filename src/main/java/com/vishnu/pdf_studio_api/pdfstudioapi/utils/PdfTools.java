package com.vishnu.pdf_studio_api.pdfstudioapi.utils;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.ColorModel;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.FilePageOrderModel;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.RangeModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
public class PdfTools {
    public static byte[] compressPdf(byte[] fileBytes, String outFileName, float compressQuality) {
        return null;
    }


    private static void compressImagesOnPage(PDPage page) throws IOException {

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

    public static byte[] reorderPdf(List<MultipartFile> files, List<FilePageOrderModel> order) throws Exception {
        Map<String, PDDocument> loadedDocs = new HashMap<>();
        Map<String, PDPageTree> pageTrees = new HashMap<>();
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            for (MultipartFile file : files) {
                final var loadedDoc = Loader.loadPDF(file.getBytes());
                loadedDocs.putIfAbsent(file.getOriginalFilename(), loadedDoc);
                pageTrees.putIfAbsent(file.getOriginalFilename(), loadedDoc.getPages());
            }
            for (final FilePageOrderModel o : order) {
                final var loadedDocPageTree = pageTrees.get(o.getFileName());
                if (loadedDocPageTree == null) throw new Exception("order filename do not match with file names");
                final PDPage page = loadedDocPageTree.get(o.getPageNo());
                document.addPage(page);
            }

            document.save(byteArrayOutputStream, CompressParameters.NO_COMPRESSION);
            final byte[] bytes = byteArrayOutputStream.toByteArray();
            return bytes;
        } finally {
            pageTrees.clear();
            loadedDocs.forEach((key, value) -> {
                try {
                    value.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
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
                for (int rangeNo = 0; rangeNo < ranges.size(); rangeNo++) {
                    final var range = ranges.get(rangeNo);
                    for (int pNo = range.getFrom(); pNo < document.getNumberOfPages() && pNo <= range.getTo(); pNo++) {
                        pt.remove(pNo);
                    }
                    document.save(baos);
                }
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
        final String toWrite = pageNoType.getType().replace("Y", String.valueOf(document.getNumberOfPages())).replace("_", " ");
        PDFont font = new PDType1Font(fontName); // You can change the font as needed

        for (int pNo = fromPage; pNo <= toPage; pNo++) {
            final String text = toWrite.replace("X", String.valueOf(pNo + 1));
            float textBounds = font.getStringWidth(text) / 1000 * size;

            PDPage page = document.getPage(pNo);


            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();

            float xCoord = switch (hPos) {
                case Postion.START -> padding.getLeft() + size;
                case Postion.CENTER -> Math.max(0, pageWidth / 2 - textBounds / 2.0f);
                case Postion.END -> Math.max(0, pageWidth - textBounds - padding.getRight());
            };

            float yCoord = switch (vPos) {
                case Postion.START -> pageHeight - size - padding.getTop();
                case Postion.CENTER -> pageHeight / 2;
                case Postion.END -> padding.getBottom();
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

    public static byte[] rotatePdf(PDDocument document,Integer globalAngle,Integer fileAngle,List<Integer> pagesAngle,List<Integer> pageToRotate,Boolean maintainRatio) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            final PDPageTree pt = document.getPages();
            if (pageToRotate.isEmpty()) {//rotate all
                for (int pNo = 0; pNo < document.getNumberOfPages(); pNo++) {
                    final Integer rAngle=getRotateAngle(globalAngle,fileAngle,pagesAngle,pNo);
                    if(rAngle==null) throw new IllegalArgumentException("invalid rotate angle [provide either global angle,file angle,page angle]");
                    final var page = pt.get(pNo);
                    rotatePdfPage(document, page, rAngle,maintainRatio);
                }
            } else {//rotate given
                for (int pNo : pageToRotate) {
                    final Integer rAngle=getRotateAngle(globalAngle,fileAngle,pagesAngle,pNo);
                    if(rAngle==null) throw new IllegalArgumentException("invalid rotate angle [provide either global angle,file angle,page angle]");
                    final var page = pt.get(pNo);
                    rotatePdfPage(document, page, rAngle,maintainRatio);
                }
            }
            document.save(baos, CompressParameters.NO_COMPRESSION);
            return baos.toByteArray();
        }
    }
    private static Integer getRotateAngle(Integer globalAngle,Integer fileAngle,List<Integer> pagesAngle,int index){
        if((pagesAngle.size()-1<index || pagesAngle.get(index)==null) && fileAngle==null && globalAngle==null) return null;
        if((pagesAngle.size()-1>=index && pagesAngle.get(index)!=null)) return pagesAngle.get(index);
        else if(fileAngle!=null) return fileAngle;
        else return globalAngle;
    }

    private static void rotatePdfPage(PDDocument document, PDPage page, Integer angle, Boolean maintainRatio) throws IOException {
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

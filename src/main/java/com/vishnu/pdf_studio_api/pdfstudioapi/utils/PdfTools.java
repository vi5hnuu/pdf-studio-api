package com.vishnu.pdf_studio_api.pdfstudioapi.utils;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.ColorModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
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
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
public class PdfTools {
    public static byte[] compressPdf(byte[] fileBytes,String outFileName,float compressQuality){
        return null;
    }


    private static void compressImagesOnPage(PDPage page) throws IOException {

    }

    public static byte[] pdfToImage(PDDocument document, Boolean singleImage, Direction direction, Quality quality,Integer imageGap) throws IOException {
        if(singleImage) return pdfToSingleImage(document,direction,quality,imageGap);
        else return pdfToImagesZip(document,quality);
    }

    public static byte[] imagesToPdf(List<MultipartFile> files) throws Exception {
        PDDocument document = new PDDocument();

        for(MultipartFile file : files){
            BufferedImage bimg = ImageIO.read(file.getInputStream());
            float width = bimg.getWidth();
            float height = bimg.getHeight();

            PDPage page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);

            PDImageXObject img = PDImageXObject.createFromByteArray(document,file.getBytes(),file.getName());
            PDPageContentStream contentStream = new PDPageContentStream(document, page);
            contentStream.drawImage(img, 0, 0);
            contentStream.close();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        document.save(byteArrayOutputStream);
        document.close();
        final byte[] bytes = byteArrayOutputStream.toByteArray();
        byteArrayOutputStream.close();
        return bytes;
    }

    public static byte[] pdfToSingleImage(PDDocument document,Direction direction, Quality quality,Integer imageGap) throws IOException {
        if(document==null) throw new IllegalArgumentException("pdf document is required");

        if(direction==null) direction=Direction.VERTICAL;
        if(quality==null) quality=Quality.LOW;
        if (imageGap==null) imageGap=0;

        PDFRenderer pdfRenderer = new PDFRenderer(document);

        // Combine all pages into a single image
        BufferedImage combinedImage = null;
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages() ; pageIndex++) {
            BufferedImage pageImage = pdfRenderer.renderImageWithDPI(pageIndex, quality.getDpi(), ImageType.RGB);
            if(combinedImage==null) combinedImage=pageImage;
            else combinedImage = direction==Direction.VERTICAL ? combineImagesVertically(combinedImage, pageImage,imageGap) : combineImagesHorizontally(combinedImage,pageImage,imageGap);
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        if(combinedImage!=null)ImageIO.write(combinedImage,"JPG",byteArrayOutputStream);
        final byte[] bytes = byteArrayOutputStream.toByteArray();
        byteArrayOutputStream.close();
        return bytes;
    }
    public static byte[] pdfToImagesZip(PDDocument document,Quality quality) throws IOException {
        if(document==null) throw new IllegalArgumentException("pdf document is required");
        if(quality==null) quality=Quality.LOW;

        try(ByteArrayOutputStream zipOutputStream = new ByteArrayOutputStream();
            ZipOutputStream zip = new ZipOutputStream(zipOutputStream);
            ByteArrayOutputStream baos = new ByteArrayOutputStream()){
            PDFRenderer pdfRenderer = new PDFRenderer(document);

            for (int pageIndex = 0; pageIndex < document.getNumberOfPages() ; pageIndex++) {
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

    private static BufferedImage combineImagesVertically(BufferedImage image1, BufferedImage image2,Integer offset) {
        if(offset==null) offset=0;

        int width = Math.max(image1.getWidth(), image2.getWidth());
        int height = image1.getHeight() + image2.getHeight()+offset;
        BufferedImage combinedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        combinedImage.createGraphics().drawImage(image1, 0, 0, null);
        combinedImage.createGraphics().drawImage(image2, 0, image1.getHeight()+offset, null);
        return combinedImage;
    }
    private static BufferedImage combineImagesHorizontally(BufferedImage image1, BufferedImage image2,Integer offset) {
        if(offset==null) offset=0;

        int width = image1.getWidth() + image2.getWidth() + offset;
        int height = Math.max(image1.getHeight() , image2.getHeight());
        BufferedImage combinedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        combinedImage.createGraphics().drawImage(image1, 0, 0, null);
        combinedImage.createGraphics().drawImage(image2, image1.getWidth()+offset, 0, null);
        return combinedImage;
    }

    public static byte[] writePageNumbersToPages(PDDocument document, Postion vPos, Postion hPos, Integer fromPage, Integer toPage, PageNoType pageNoType, ColorModel fillColor, Padding padding, Integer size, Standard14Fonts.FontName fontName) throws IOException {
        final String toWrite=pageNoType.getType().replace("Y",String.valueOf(document.getNumberOfPages())).replace("_"," ");
        PDFont font = new PDType1Font(fontName); // You can change the font as needed

        for (int pNo = fromPage; pNo <= toPage; pNo++) {
            final String text=toWrite.replace("X",String.valueOf(pNo+1));
            float textBounds = font.getStringWidth(text)/1000*size;

            PDPage page = document.getPage(pNo);


            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();

            float xCoord =  switch (hPos) {
                case Postion.START -> padding.getLeft() + size;
                case Postion.CENTER -> Math.max(0,pageWidth/2 - textBounds/2.0f);
                case Postion.END -> Math.max(0,pageWidth - textBounds-padding.getRight());
            };

            float yCoord =  switch (vPos) {
                case Postion.START -> pageHeight - size - padding.getTop();
                case Postion.CENTER -> pageHeight / 2;
                case Postion.END -> padding.getBottom();
            };

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                contentStream.beginText();
                contentStream.setFont(font, size);
                contentStream.setNonStrokingColor(fillColor.color());
                contentStream.newLineAtOffset(xCoord,yCoord);
                contentStream.showText(text);
                contentStream.endText();
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        document.save(baos);
        final byte[] bytes = baos.toByteArray();
        baos.close();
        return bytes;
    }

    public static byte[] protectPdf(PDDocument document,String ownerPassword, String userPassword, Set<UserAccessPermission> userAccessPermissions) throws Exception {
        if (document.isEncrypted()) throw new Exception("document is already protected.");

        final AccessPermission ap = getUserAccessPermission(userAccessPermissions);
        final StandardProtectionPolicy spp=new StandardProtectionPolicy(ownerPassword,userPassword,ap);
        spp.setEncryptionKeyLength(256);
        document.protect(spp);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        document.save(baos);
        final byte[] bytes = baos.toByteArray();
        baos.close();
        return bytes;
    }

    private static AccessPermission getUserAccessPermission(Set<UserAccessPermission> userAccessPermissions) {
        final AccessPermission ap=new AccessPermission();//default user has owner permission
        if(!userAccessPermissions.isEmpty()){
            ap.setCanFillInForm(userAccessPermissions.contains(UserAccessPermission.FILL_IN_FORM));
            ap.setCanAssembleDocument(userAccessPermissions.contains(UserAccessPermission.ASSEMBLE_DOCUMENT));
            ap.setCanExtractContent(userAccessPermissions.contains(UserAccessPermission.EXTRACT));
            ap.setCanModify(userAccessPermissions.contains(UserAccessPermission.MODIFICATION));
            ap.setCanPrint(userAccessPermissions.contains(UserAccessPermission.PRINT));
            ap.setCanExtractForAccessibility(userAccessPermissions.contains(UserAccessPermission.EXTRACT_FOR_ACCESSIBILITY));
            ap.setCanModifyAnnotations(userAccessPermissions.contains(UserAccessPermission.MODIFY_ANNOTATIONS));
            ap.setCanPrintFaithful(userAccessPermissions.contains(UserAccessPermission.FAITHFUL_PRINT));
            if(userAccessPermissions.contains(UserAccessPermission.READ_ONLY)) ap.setReadOnly();
        }
        return ap;
    }

}

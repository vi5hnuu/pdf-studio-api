package com.vishnu.pdf_studio_api.pdfstudioapi.utils;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.web.multipart.MultipartFile;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF to Microsoft Office format converters.
 * Text-extraction based — preserves text content and basic paragraph structure.
 * Does not reproduce pixel-perfect layout, images, or complex table formatting.
 */
public class OfficeConvertTools {

    // ── PDF → DOCX ─────────────────────────────────────────────────────────────

    /**
     * Extracts text from each PDF page and writes it as paragraphs in a DOCX document.
     * Page breaks are inserted between pages.
     */
    public static byte[] pdfToDocx(byte[] pdfBytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfBytes);
             XWPFDocument docx = new XWPFDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            for (int pageIdx = 0; pageIdx < doc.getNumberOfPages(); pageIdx++) {
                stripper.setStartPage(pageIdx + 1);
                stripper.setEndPage(pageIdx + 1);
                String pageText = stripper.getText(doc);

                // Write each line as a paragraph
                String[] lines = pageText.split("\\r?\\n");
                for (String line : lines) {
                    XWPFParagraph para = docx.createParagraph();
                    XWPFRun run = para.createRun();
                    run.setText(line.isEmpty() ? "" : line);
                    run.setFontSize(11);
                    run.setFontFamily("Calibri");
                }

                // Page break between pages (not after last page)
                if (pageIdx < doc.getNumberOfPages() - 1) {
                    XWPFParagraph breakPara = docx.createParagraph();
                    breakPara.setPageBreak(true);
                }
            }

            docx.write(baos);
            return baos.toByteArray();
        }
    }

    // ── PDF → XLSX ─────────────────────────────────────────────────────────────

    /**
     * Extracts text from each PDF page into a separate sheet.
     * Each line of text becomes a row; tab-separated or multi-space separated tokens
     * are split into individual cells (best-effort table detection).
     */
    public static byte[] pdfToXlsx(byte[] pdfBytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfBytes);
             XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            for (int pageIdx = 0; pageIdx < doc.getNumberOfPages(); pageIdx++) {
                stripper.setStartPage(pageIdx + 1);
                stripper.setEndPage(pageIdx + 1);
                String pageText = stripper.getText(doc);

                XSSFSheet sheet = wb.createSheet("Page " + (pageIdx + 1));
                String[] lines = pageText.split("\\r?\\n");
                int rowNum = 0;
                for (String line : lines) {
                    if (line.isBlank()) continue;
                    XSSFRow row = sheet.createRow(rowNum++);
                    // Split on 2+ consecutive spaces or tabs (column separator heuristic)
                    String[] cells = line.split("\\t|(?<=\\S) {2,}(?=\\S)");
                    for (int c = 0; c < cells.length; c++) {
                        row.createCell(c).setCellValue(cells[c].trim());
                    }
                }
                // Auto-size first few columns
                for (int c = 0; c < 10; c++) {
                    sheet.autoSizeColumn(c);
                }
            }

            wb.write(baos);
            return baos.toByteArray();
        }
    }

    // ── PDF → PPTX ────────────────────────────────────────────────────────────

    /**
     * Creates one PowerPoint slide per PDF page with the extracted text in a text box.
     * Slide dimensions match A4 landscape (common presentation size).
     */
    public static byte[] pdfToPptx(byte[] pdfBytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfBytes);
             XMLSlideShow pptx = new XMLSlideShow();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            // Set slide size to A4 landscape (in EMUs: 1 inch = 914400 EMU)
            pptx.setPageSize(new java.awt.Dimension(1270, 952)); // 10in x 7.5in in 72dpi units

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            XSLFSlideLayout blankLayout = pptx.getSlideMasters().get(0).getLayout(SlideLayout.BLANK);

            for (int pageIdx = 0; pageIdx < doc.getNumberOfPages(); pageIdx++) {
                stripper.setStartPage(pageIdx + 1);
                stripper.setEndPage(pageIdx + 1);
                String pageText = stripper.getText(doc).trim();

                XSLFSlide slide = pptx.createSlide(blankLayout);

                // Add a text box covering most of the slide
                XSLFTextBox tb = slide.createTextBox();
                tb.setAnchor(new java.awt.Rectangle(50, 50, 1170, 852));

                // First paragraph as "title-like" (larger font)
                String[] lines = pageText.split("\\r?\\n");
                boolean firstNonEmpty = true;
                for (String line : lines) {
                    if (line.isBlank()) continue;
                    XSLFTextParagraph para = tb.addNewTextParagraph();
                    XSLFTextRun run = para.addNewTextRun();
                    run.setText(line);
                    if (firstNonEmpty) {
                        run.setFontSize(20.0);
                        run.setBold(true);
                        firstNonEmpty = false;
                    } else {
                        run.setFontSize(14.0);
                    }
                }

                // Page number label bottom-right
                XSLFTextBox pageLabel = slide.createTextBox();
                pageLabel.setAnchor(new java.awt.Rectangle(1100, 900, 150, 30));
                XSLFTextParagraph lblPara = pageLabel.addNewTextParagraph();
                lblPara.setTextAlign(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.RIGHT);
                XSLFTextRun lblRun = lblPara.addNewTextRun();
                lblRun.setText("Page " + (pageIdx + 1));
                lblRun.setFontSize(10.0);
            }

            pptx.write(baos);
            return baos.toByteArray();
        }
    }
}

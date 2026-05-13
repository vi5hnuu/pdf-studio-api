package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.*;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

@Service
@Slf4j
public class ImageService {

    // ── Compress ──────────────────────────────────────────────────────────────

    /**
     * Re-encodes the image as JPEG at the requested quality (1–100, default 75).
     * Non-JPEG inputs are accepted — transparent pixels are composited on white.
     */
    public ResponseEntity<Resource> compressImage(CompressImageRequest req, MultipartFile file) {
        if (req == null) req = new CompressImageRequest();
        int quality = req.getQuality() != null ? clamp(req.getQuality(), 1, 100) : 75;
        String outFileName = defaultName(req.getOutFileName(),
                stripExtension(file.getOriginalFilename()) + "_compressed");
        try {
            BufferedImage img = readImage(file);
            byte[] bytes = encodeJpeg(toRgb(img), quality);
            return fileResponse(bytes, outFileName + ".jpg", "image/jpeg");
        } catch (Exception e) {
            throw new RuntimeException("Failed to compress image: " + e.getMessage(), e);
        }
    }

    // ── Convert to JPG ────────────────────────────────────────────────────────

    /**
     * Converts any supported image (PNG, BMP, GIF) to JPEG.
     * Transparent pixels are composited onto a white background before encoding.
     */
    public ResponseEntity<Resource> convertToJpg(ConvertToJpgRequest req, MultipartFile file) {
        if (req == null) req = new ConvertToJpgRequest();
        int quality = req.getQuality() != null ? clamp(req.getQuality(), 1, 100) : 90;
        String outFileName = defaultName(req.getOutFileName(), stripExtension(file.getOriginalFilename()));
        try {
            BufferedImage img = readImage(file);
            byte[] bytes = encodeJpeg(toRgb(img), quality);
            return fileResponse(bytes, outFileName + ".jpg", "image/jpeg");
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert image to JPG: " + e.getMessage(), e);
        }
    }

    // ── Convert from JPG ──────────────────────────────────────────────────────

    /**
     * Converts a JPEG (or any image) to PNG or BMP.
     * Defaults to PNG if format is missing or unrecognised.
     */
    public ResponseEntity<Resource> convertFromJpg(ConvertFromJpgRequest req, MultipartFile file) {
        if (req == null) req = new ConvertFromJpgRequest();
        String fmt = (req.getFormat() != null && req.getFormat().equalsIgnoreCase("BMP")) ? "BMP" : "PNG";
        String ext = fmt.toLowerCase();
        String outFileName = defaultName(req.getOutFileName(), stripExtension(file.getOriginalFilename()));
        try {
            BufferedImage img = readImage(file);
            BufferedImage out = fmt.equals("PNG") ? img : toRgb(img);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(out, fmt, baos);
            return fileResponse(baos.toByteArray(), outFileName + "." + ext, "image/" + ext);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert image from JPG: " + e.getMessage(), e);
        }
    }

    // ── Resize ────────────────────────────────────────────────────────────────

    /**
     * Resizes using bicubic interpolation.
     * If maintainAspectRatio is true and one dimension is 0, it is computed proportionally.
     * Output format matches the input (JPEG stays JPEG, others become PNG).
     */
    public ResponseEntity<Resource> resizeImage(ResizeImageRequest req, MultipartFile file) {
        if (req == null) req = new ResizeImageRequest();
        try {
            BufferedImage src = readImage(file);
            int srcW = src.getWidth();
            int srcH = src.getHeight();

            int targetW = req.getWidth() != null ? req.getWidth() : 0;
            int targetH = req.getHeight() != null ? req.getHeight() : 0;
            boolean keepAspect = Boolean.TRUE.equals(req.getMaintainAspectRatio());

            if (keepAspect) {
                if (targetW > 0 && targetH <= 0) {
                    targetH = (int) Math.round((double) srcH / srcW * targetW);
                } else if (targetH > 0 && targetW <= 0) {
                    targetW = (int) Math.round((double) srcW / srcH * targetH);
                } else if (targetW > 0 && targetH > 0) {
                    double scale = Math.min((double) targetW / srcW, (double) targetH / srcH);
                    targetW = (int) Math.round(srcW * scale);
                    targetH = (int) Math.round(srcH * scale);
                }
            }
            if (targetW <= 0) targetW = srcW;
            if (targetH <= 0) targetH = srcH;

            BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, targetW, targetH, null);
            g.dispose();

            String name = file.getOriginalFilename();
            boolean isJpeg = name != null &&
                    (name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".jpeg"));
            String outFileName = defaultName(req.getOutFileName(),
                    stripExtension(name) + "_resized");

            if (isJpeg) {
                byte[] bytes = encodeJpeg(toRgb(resized), 92);
                return fileResponse(bytes, outFileName + ".jpg", "image/jpeg");
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(resized, "PNG", baos);
                return fileResponse(baos.toByteArray(), outFileName + ".png", "image/png");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to resize image: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BufferedImage readImage(MultipartFile file) throws IOException {
        BufferedImage img = ImageIO.read(file.getInputStream());
        if (img == null)
            throw new IOException("Unsupported or corrupt image: " + file.getOriginalFilename());
        return img;
    }

    /** Composites image (including alpha channel) onto a white RGB canvas for JPEG encoding. */
    private BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    /** Encodes a BufferedImage as JPEG bytes at the given quality (1–100). */
    private byte[] encodeJpeg(BufferedImage img, int quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("No JPEG ImageWriter found");
        ImageWriter writer = writers.next();
        JPEGImageWriteParam params = new JPEGImageWriteParam(null);
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(quality / 100f);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), params);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    private ResponseEntity<Resource> fileResponse(byte[] bytes, String filename, String mimeType) {
        ByteArrayResource body = new ByteArrayResource(bytes);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename);
        headers.setContentLength(bytes.length);
        headers.setContentType(MediaType.parseMediaType(mimeType));
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private String defaultName(String provided, String fallback) {
        return (provided != null && !provided.isBlank()) ? provided
                : (fallback != null ? fallback : "output");
    }

    private String stripExtension(String filename) {
        if (filename == null) return "image";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}

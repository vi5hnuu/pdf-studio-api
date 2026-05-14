package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.FilterType;
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
import java.awt.geom.Point2D;
import java.awt.image.*;
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

    // ── Filter ────────────────────────────────────────────────────────────────

    /**
     * Applies a visual filter to the image. Intensity (0.0–2.0) controls the strength.
     * Returns a JPEG-encoded filtered image.
     */
    public ResponseEntity<Resource> applyFilter(FilterImageRequest req, MultipartFile file) {
        if (req == null) req = new FilterImageRequest();
        FilterType type = req.getFilterType() != null ? req.getFilterType() : FilterType.GRAYSCALE;
        float intensity = req.getIntensity();
        String outFileName = defaultName(req.getOutFileName(),
                stripExtension(file.getOriginalFilename()) + "_filtered");
        try {
            BufferedImage src = readImage(file);
            BufferedImage result = applyFilterToImage(src, type, intensity);
            byte[] bytes = encodeJpeg(toRgb(result), 90);
            return fileResponse(bytes, outFileName + ".jpg", "image/jpeg");
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply filter: " + e.getMessage(), e);
        }
    }

    private BufferedImage applyFilterToImage(BufferedImage src, FilterType type, float intensity) {
        return switch (type) {
            case GRAYSCALE -> toGrayscale(src);
            case SEPIA -> applySepia(src, intensity);
            case SHARPEN -> applySharpen(src, intensity);
            case BRIGHTNESS -> applyBrightness(src, intensity);
            case CONTRAST -> applyContrast(src, intensity);
            case VINTAGE -> applyVintage(src, intensity);
        };
    }

    private BufferedImage toGrayscale(BufferedImage src) {
        BufferedImage gray = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        // Convert back to RGB so JPEG encoding works uniformly
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = rgb.createGraphics();
        g2.drawImage(gray, 0, 0, null);
        g2.dispose();
        return rgb;
    }

    private BufferedImage applySepia(BufferedImage src, float intensity) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int rgb = src.getRGB(x, y);
                float r = ((rgb >> 16) & 0xff) / 255f;
                float g = ((rgb >> 8) & 0xff) / 255f;
                float b = (rgb & 0xff) / 255f;
                // Standard sepia matrix
                float sr = Math.min(1f, r * 0.393f + g * 0.769f + b * 0.189f);
                float sg = Math.min(1f, r * 0.349f + g * 0.686f + b * 0.168f);
                float sb = Math.min(1f, r * 0.272f + g * 0.534f + b * 0.131f);
                // Blend with original based on intensity
                int nr = Math.round((sr * intensity + r * (1f - intensity)) * 255);
                int ng = Math.round((sg * intensity + g * (1f - intensity)) * 255);
                int nb = Math.round((sb * intensity + b * (1f - intensity)) * 255);
                out.setRGB(x, y, (clamp(nr, 0, 255) << 16) | (clamp(ng, 0, 255) << 8) | clamp(nb, 0, 255));
            }
        }
        return out;
    }

    private BufferedImage applySharpen(BufferedImage src, float intensity) {
        float i = Math.max(0.1f, intensity);
        float[] kernel = {0, -i, 0, -i, 1 + 4 * i, -i, 0, -i, 0};
        Kernel k = new Kernel(3, 3, kernel);
        ConvolveOp op = new ConvolveOp(k, ConvolveOp.EDGE_NO_OP, null);
        return op.filter(toRgb(src), null);
    }

    private BufferedImage applyBrightness(BufferedImage src, float intensity) {
        // intensity 1.0 = no change, >1 brighter, <1 darker
        RescaleOp op = new RescaleOp(intensity, 0, null);
        return op.filter(toRgb(src), null);
    }

    private BufferedImage applyContrast(BufferedImage src, float intensity) {
        // Contrast: scale around mid-point (128)
        float scale = intensity;
        float offset = 128f * (1f - scale);
        RescaleOp op = new RescaleOp(
                new float[]{scale, scale, scale},
                new float[]{offset, offset, offset},
                null);
        return op.filter(toRgb(src), null);
    }

    private BufferedImage applyVintage(BufferedImage src, float intensity) {
        // Vintage = sepia + dark radial vignette
        BufferedImage sepia = applySepia(src, intensity);
        int w = sepia.getWidth();
        int h = sepia.getHeight();
        BufferedImage vintage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = vintage.createGraphics();
        g.drawImage(sepia, 0, 0, null);

        // Radial vignette: transparent center, dark edges
        Point2D center = new Point2D.Float(w / 2f, h / 2f);
        float radius = (float) Math.sqrt(w * w + h * h) / 2f;
        float[] dist = {0f, 0.6f, 1f};
        Color[] colors = {
                new Color(0, 0, 0, 0),
                new Color(0, 0, 0, 0),
                new Color(0, 0, 0, Math.round(180 * intensity))
        };
        g.setPaint(new RadialGradientPaint(center, radius, dist, colors));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return vintage;
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

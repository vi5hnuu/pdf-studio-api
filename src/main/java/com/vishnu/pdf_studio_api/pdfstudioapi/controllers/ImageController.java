package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.*;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.ImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/image-studio")
@RequiredArgsConstructor
public class ImageController {
    private final ImageService imageService;

    /** Compress an image to JPEG at the specified quality (1–100). Default 75. */
    @PostMapping(value = "/compress-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> compressImage(
            @RequestPart(value = "compress-image-info", required = false) CompressImageRequest req,
            @RequestPart("file") MultipartFile file) {
        return imageService.compressImage(req, file);
    }

    /** Convert any image (PNG, BMP, GIF) to JPEG at the specified quality. Default 90. */
    @PostMapping(value = "/convert-to-jpg", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> convertToJpg(
            @RequestPart(value = "convert-to-jpg-info", required = false) ConvertToJpgRequest req,
            @RequestPart("file") MultipartFile file) {
        return imageService.convertToJpg(req, file);
    }

    /** Convert a JPEG to PNG or BMP. Default target format: PNG. */
    @PostMapping(value = "/convert-from-jpg", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> convertFromJpg(
            @RequestPart(value = "convert-from-jpg-info", required = false) ConvertFromJpgRequest req,
            @RequestPart("file") MultipartFile file) {
        return imageService.convertFromJpg(req, file);
    }

    /** Resize an image to the specified width × height using bicubic interpolation. */
    @PostMapping(value = "/resize-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> resizeImage(
            @RequestPart(value = "resize-image-info", required = false) ResizeImageRequest req,
            @RequestPart("file") MultipartFile file) {
        return imageService.resizeImage(req, file);
    }

    /** Apply a visual filter (grayscale, sepia, sharpen, brightness, contrast, vintage) to an image. */
    @PostMapping(value = "/filter-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> filterImage(
            @RequestPart(value = "filter-image-info", required = false) FilterImageRequest req,
            @RequestPart("file") MultipartFile file) {
        return imageService.applyFilter(req, file);
    }
}

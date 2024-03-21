package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

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
    @PostMapping(value = "/compress-image",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> compressImage(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return imageService.compressImage(a,multipartFile);
    }
    @PostMapping(value = "/convert-to-jpg",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> convertToJpg(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return imageService.convertToJpg(a,multipartFile);
    }
    @PostMapping(value = "/convert-from-jpg",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> convertFromJpg(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return imageService.convertFromJpg(a,multipartFile);
    }
    @PostMapping(value = "/resize-image",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> resizeImage(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return imageService.resizeImage(a,multipartFile);
    }
}

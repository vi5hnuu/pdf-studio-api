package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
public class ImageService {
    public ResponseEntity<Resource> compressImage(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> convertToJpg(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> convertFromJpg(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> resizeImage(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
}

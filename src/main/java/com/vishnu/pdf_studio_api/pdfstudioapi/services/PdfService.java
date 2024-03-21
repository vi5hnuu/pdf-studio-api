package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.dto.request.CompressPdfRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@Slf4j
public class PdfService {
    public ResponseEntity<Resource> mergePdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> splitPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> compressPdf(String outFileName,float compressQuality,MultipartFile file){
        try{
            final byte[] bytes = file.getBytes();
        }catch (IOException e) {
            throw new RuntimeException(e);
        }
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> pdfToWord(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> pdfToPowerPoint(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> pdfToExcel(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> wordToPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> powerpointToPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> excelToPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> editPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> pdfToJpg(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> imageToPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> pageNumbers(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> waterMark(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> rotatePdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> unlockPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> protectPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> organizePdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> repairPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> signPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> extractTxt(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> createPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
    public ResponseEntity<Resource> ocrPdf(@RequestPart() Object a, @RequestPart MultipartFile multipartFile){
        return ResponseEntity.status(200).body(null);
    }
}

package com.vishnu.pdf_studio_api.pdfstudioapi.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Slf4j
public class PdfTools {
    public static byte[] compressPdf(MultipartFile file,String outFileName,float compressQuality){
        return null;
    }
}

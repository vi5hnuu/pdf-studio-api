package com.vishnu.pdf_studio_api.pdfstudioapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Required for CreditGrantIpCleanupJob; @Scheduled is inert without it.
@EnableScheduling
public class PdfStudioApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(PdfStudioApiApplication.class, args);
	}

}

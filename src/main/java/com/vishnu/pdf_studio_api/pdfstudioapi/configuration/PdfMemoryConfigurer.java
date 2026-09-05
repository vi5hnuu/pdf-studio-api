package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import com.vishnu.pdf_studio_api.pdfstudioapi.util.PdfDocuments;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Pushes the configured scratch-file threshold into {@link PdfDocuments}.
 *
 * <p>{@code PdfTools} is a static utility with no Spring wiring, so the threshold it consults has to
 * be a static field. This bean is the one place that field is written, keeping the value
 * configurable through {@code app.load.scratch-file-threshold-bytes} rather than hard-coded.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PdfMemoryConfigurer {

    private final LoadProperties loadProperties;
    private final UploadProperties uploadProperties;

    @PostConstruct
    void apply() {
        PdfDocuments.setDefaultThreshold(loadProperties.getScratchFileThresholdBytes());
        PdfDocuments.setMaxPages(uploadProperties.getMaxPages());
        log.info("PDFBox spills to a temp-file stream cache at {} bytes and above; page cap {}",
                PdfDocuments.defaultThreshold(), PdfDocuments.maxPages());
    }
}

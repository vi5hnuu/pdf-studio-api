package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code app.load.*} — how much concurrent heavy work this instance will admit.
 *
 * <p>Tomcat serves up to 200 requests concurrently by default. Rendering or converting a large PDF
 * can hold hundreds of megabytes, so without a cap a modest burst of heavy requests exhausts the
 * heap and takes the whole instance down. Bounding admission turns that failure into a fast,
 * honest 503 with {@code Retry-After}.
 */
@Component
@ConfigurationProperties(prefix = "app.load")
@Getter
@Setter
public class LoadProperties {

    /**
     * Concurrent heavy operations allowed. Defaults to the CPU count: these are CPU- and
     * memory-bound, so admitting more than we can actually run only inflates memory.
     */
    private int maxConcurrentHeavy = Math.max(2, Runtime.getRuntime().availableProcessors());

    /** How long a request waits for a slot before being told to retry. */
    private int queueTimeoutSeconds = 20;

    /** Advertised in the {@code Retry-After} header when the limiter is saturated. */
    private int retryAfterSeconds = 15;

    /**
     * Documents at or above this size get PDFBox's temp-file stream cache instead of the
     * memory-only one. Below it, staying in memory is faster and the footprint is irrelevant.
     */
    private long scratchFileThresholdBytes = 8L * 1024 * 1024;
}

package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code app.upload.*} — the guards on what a single request may ask this service to process.
 *
 * <p>Separate from the multipart size limit (which bounds bytes on the wire): these bound the
 * <i>work</i>. A 3 MB PDF can still hold 20 000 pages, and several tools render every page, so page
 * count is the metric that actually predicts CPU and memory.
 */
@Component
@ConfigurationProperties(prefix = "app.upload")
@Getter
@Setter
public class UploadProperties {

    /** Max files accepted in one multi-file request (merge, image-to-pdf). */
    private int maxFiles = 50;

    /** Max pages in a single PDF this service will process. */
    private int maxPages = 2000;

    /**
     * Max pages for tools that rasterise every page (pdf-to-jpg, remove-blank-pages, analyze,
     * grayscale). Rendering is far costlier per page than structural edits, so it is capped lower.
     */
    private int maxRenderPages = 500;
}

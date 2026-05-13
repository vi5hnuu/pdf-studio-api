package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request params for image resizing.
 * width / height: target pixel dimensions (both required unless maintainAspectRatio is true, in which case one may be 0).
 * maintainAspectRatio: if true and only width or height is non-zero, the other is computed proportionally.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ResizeImageRequest {
    private String outFileName;
    private Integer width;
    private Integer height;
    private Boolean maintainAspectRatio;
}

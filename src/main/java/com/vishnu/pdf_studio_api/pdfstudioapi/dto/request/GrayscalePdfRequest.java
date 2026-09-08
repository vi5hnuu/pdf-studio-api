package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class GrayscalePdfRequest {
    private String outFileName;
    /**
     * 0-indexed pages to apply this to. Empty or absent applies to the whole document, which is
     * what this tool always did.
     */
    private List<Integer> pages;
}

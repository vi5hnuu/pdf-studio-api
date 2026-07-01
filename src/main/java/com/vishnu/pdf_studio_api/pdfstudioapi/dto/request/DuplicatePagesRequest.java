package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class DuplicatePagesRequest {
    private String outFileName;
    private List<Integer> pages; // 0-indexed page numbers to duplicate
    // Integer (boxed) so Jackson doesn't default missing field to 0
    private Integer count = 1;   // how many copies of each selected page to insert (legacy: same count for all pages)
    // Preferred: per-page copy counts, keyed by 0-indexed page number. When
    // present this takes precedence over the flat `pages` + `count` pair.
    private Map<Integer, Integer> pageCounts;
}

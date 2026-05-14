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
public class DuplicatePagesRequest {
    private String outFileName;
    private List<Integer> pages; // 0-indexed page numbers to duplicate
    // Integer (boxed) so Jackson doesn't default missing field to 0
    private Integer count = 1;   // how many copies of each selected page to insert
}

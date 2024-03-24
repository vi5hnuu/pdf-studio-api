package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.SplitType;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.RangeModel;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/*
* SPLIT_BY_RANGE -> use ranges array
* FIXED_RANGE -> use fixed
* DELETE_PAGES -> use ranges array
* EXTRACT_ALL_PAGES -> x
* */
@Getter
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SplitPdfRequest {
    private String outFileName;
    @NotNull(message = "type cannot be null") private SplitType type;
    private Integer fixed;
    private List<RangeModel> ranges;
}

package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.SizeUnit;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A repricing of one tool.
 *
 * <p>Every field is required so a partial body cannot silently reset a component of the
 * price — repricing is a money operation, and "unset means keep" is too easy to get wrong
 * from a hand-written request.
 */
public record ToolCostUpdateRequest(
        @NotNull @Min(0) Integer baseCredits,
        @NotNull SizeUnit sizeUnit,
        @NotNull @Min(0) Integer creditsPerUnit,
        @NotNull @Min(1) Long unitSize,
        @NotNull Boolean active
) {}

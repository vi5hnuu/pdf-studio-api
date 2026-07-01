package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Spec for turning a PDF into a fillable form. Each field becomes a real
 * interactive AcroForm field (so the output stays fillable in any reader).
 * Coordinates are in PDF points with a TOP-LEFT origin (the backend flips Y to
 * PDFBox's bottom-left origin), matching how the other editor tools send rects.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CreateFormRequest {
    private String outFileName;
    private List<FormFieldSpec> fields;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class FormFieldSpec {
        // text | multiline | checkbox | radio | dropdown | date | signature
        private String type;
        private String name;          // field name; for radio this is the group name
        private int page;             // 0-indexed
        private float x;              // top-left origin, PDF points
        private float y;
        private float width;
        private float height;
        private String value;         // default value / selected option
        private List<String> options; // dropdown choices
        private String exportValue;   // radio option value (per widget)
        private Float fontSize;        // text font size (0 = auto)
        private Boolean required;
        private Boolean checked;       // checkbox / radio: on by default
    }
}

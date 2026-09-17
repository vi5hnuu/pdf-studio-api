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

        // ── Rich properties (all optional, so older clients keep working) ──────────────
        private String tooltip;          // /TU help text
        private Boolean readOnly;        // filled in already, not editable
        private Integer maxLength;       // /MaxLen character cap
        private Boolean comb;            // divide the box into maxLength cells
        private Integer alignment;       // /Q quadding: 0 left, 1 centre, 2 right
        private Boolean multiSelect;     // list box accepting more than one choice
        private String format;           // number | email | phone | date — drives format actions
        private String validationPattern; // regex the value must match
        private String dateFormat;        // dd/mm/yyyy | mm/dd/yyyy | yyyy-mm-dd
        private Condition condition;      // show only when another field matches
        private CalculationSpec calculation; // value derived from other fields

        @Getter
        @Setter
        @NoArgsConstructor
        @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
        public static class Condition {
            private String parentField;
            private String operator; // equals | notEquals | contains | isEmpty | ...
            private String value;
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
        public static class CalculationSpec {
            private String function;      // SUM | AVG | PRD | MIN | MAX
            private List<String> fields;
        }
    }
}

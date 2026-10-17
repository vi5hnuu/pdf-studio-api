package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Set;

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

    @NotEmpty(message = "fields must contain at least one field")
    @Valid
    private List<FormFieldSpec> fields;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class FormFieldSpec {
        /**
         * Every type the builder knows, so an unknown one can be rejected by name instead of
         * falling through to the text branch.
         *
         * <p>A typo'd "combobox" used to ship as a text box: the editor showed a dropdown, the
         * PDF had a text field, and nothing anywhere said so.
         */
        public static final Set<String> TYPES = Set.of(
                "text", "multiline", "number", "email", "phone", "date",
                "checkbox", "radio", "dropdown", "listbox", "signature", "pushbutton");

        private String type;
        private String name;          // field name; for radio this is the group name

        @PositiveOrZero(message = "page must be 0 or greater")
        private int page;             // 0-indexed

        @PositiveOrZero(message = "x must be 0 or greater")
        private float x;              // top-left origin, PDF points

        @PositiveOrZero(message = "y must be 0 or greater")
        private float y;

        @Positive(message = "width must be greater than 0")
        private float width;

        @Positive(message = "height must be greater than 0")
        private float height;
        private String value;         // default value / selected option
        private List<String> options; // dropdown choices
        private String exportValue;   // radio option value (per widget)
        @PositiveOrZero(message = "font_size must be 0 (auto) or greater")
        private Float fontSize;        // text font size (0 = auto)
        private Boolean required;
        private Boolean checked;       // checkbox / radio: on by default

        // ── Rich properties (all optional, so older clients keep working) ──────────────
        // A caption drawn on the page beside the widget. Not the same as `tooltip` below, which
        // is /TU: hover help that never appears on paper and that most mobile readers do not
        // show at all. A radio group's options are otherwise three identical circles in the
        // output with nothing to say which is "Savings" and which is "Current".
        private String label;
        private Float labelSize;         // point size for the caption; null/0 uses the default
        private String labelPosition;    // right | left | above | below (default right)

        private String tooltip;          // /TU help text
        private Boolean readOnly;        // filled in already, not editable
        @PositiveOrZero(message = "max_length must be 0 or greater")
        private Integer maxLength;       // /MaxLen character cap
        private Boolean comb;            // divide the box into maxLength cells
        @Min(value = 0, message = "alignment must be 0, 1 or 2")
        @Max(value = 2, message = "alignment must be 0, 1 or 2")
        private Integer alignment;       // /Q quadding: 0 left, 1 centre, 2 right
        private Boolean multiSelect;     // list box accepting more than one choice
        private String format;           // number | email | phone | date — drives format actions
        @Size(max = 512, message = "validation_pattern is too long")
        private String validationPattern; // regex the value must match

        /**
         * Minimum character count, enforced as a keystroke/validate script.
         *
         * <p>The editor has always offered this and the in-app runtime has always enforced it,
         * but it was never put on the wire at all — so a form filled in any other reader
         * ignored it.
         */
        @PositiveOrZero(message = "min_length must be 0 or greater")
        private Integer minLength;

        // Numeric bounds, enforced as a validate action. These were editor-only until now: set
        // in the inspector, stored in the draft, enforced by the in-app runtime and invisible to
        // every other PDF reader.
        private Double min;
        private Double max;

        // Number presentation. Previously hardcoded to two decimals with a separator, which
        // turned a PIN or an account number into "123,456.00" with no way for the author to
        // stop it. Null means the default below (no decimals, no separator), which mangles
        // nothing; an amount field opts in to 2.
        @Min(value = 0, message = "decimal_places must be between 0 and 4")
        @Max(value = 4, message = "decimal_places must be between 0 and 4")
        private Integer decimalPlaces;   // 0-4
        private Boolean groupDigits;     // thousands separator
        private String dateFormat;        // dd/mm/yyyy | mm/dd/yyyy | yyyy-mm-dd
        private Condition condition;      // show only when another field matches
        private CalculationSpec calculation; // value derived from other fields

        // ── Appearance ────────────────────────────────────────────────────────────────
        // Every widget was 1pt solid grey on a 0.96 grey fill in Helvetica black, hardcoded,
        // with no way for an author to change any of it. The editor even previewed styling the
        // output could not carry.
        private String borderColor;      // #RRGGBB
        private String backgroundColor;  // #RRGGBB, or "none" for transparent
        private String textColor;        // #RRGGBB
        @PositiveOrZero(message = "border_width must be 0 or greater")
        private Float borderWidth;       // points; 0 removes the border
        private String borderStyle;      // solid | dashed | beveled | inset | underline
        private String fontFamily;       // helvetica | times | courier
        @Min(value = 0, message = "rotation must be 0, 90, 180 or 270")
        @Max(value = 270, message = "rotation must be 0, 90, 180 or 270")
        private Integer rotation;        // /MK /R, in degrees

        // ── Behaviour flags ───────────────────────────────────────────────────────────
        private Boolean noExport;        // /Ff bit 3: kept out of an exported data set
        private Boolean password;        // text: masked as it is typed
        private Boolean doNotSpellCheck;
        private Boolean doNotScroll;     // text: refuse input past the visible box
        private Boolean editable;        // dropdown the filler may type into (/Ff Edit)
        private Boolean sortOptions;     // /Ff Sort: reader shows the options sorted
        private Boolean radiosInUnison;  // same-export radios toggle together
        private Boolean hidden;          // not shown and not printed
        private Boolean noPrint;         // on screen only. setPrinted(true) used to be forced

        /**
         * Per-option export values for a dropdown or list box.
         *
         * <p>Only the display list was ever sent, so the value the PDF recorded was forced to be
         * the text shown — "United Kingdom" where the form wanted "GB". Ignored unless it has
         * exactly as many entries as {@code options}.
         */
        private List<String> optionValues;

        // ── Push button ───────────────────────────────────────────────────────────────
        // The one AcroForm type the builder had no way to produce, which ruled out the whole
        // action class: submit, reset, print and open-a-link buttons.
        private String action;           // submit | reset | print | uri
        private String actionTarget;     // submit URL or uri destination
        private String caption;          // button face text

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

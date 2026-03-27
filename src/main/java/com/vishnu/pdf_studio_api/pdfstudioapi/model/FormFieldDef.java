package com.vishnu.pdf_studio_api.pdfstudioapi.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class FormFieldDef {
    public enum FieldType { TEXT, CHECKBOX }

    private FieldType type;
    private String name;
    /** 0-indexed page number */
    private int page;
    /** x from left edge of page in points */
    private float x;
    /** y from bottom edge of page in points */
    private float y;
    private float width;
    private float height;
    private boolean multiline;
    private String defaultValue;
}

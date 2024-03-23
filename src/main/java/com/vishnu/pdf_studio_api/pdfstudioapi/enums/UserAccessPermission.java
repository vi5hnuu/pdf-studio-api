package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import lombok.Getter;


@Getter
public enum UserAccessPermission {
    PRINT(3),
    MODIFICATION(4),
    EXTRACT(5),
    MODIFY_ANNOTATIONS(6),
    FILL_IN_FORM(9),
    EXTRACT_FOR_ACCESSIBILITY(10),
    ASSEMBLE_DOCUMENT(11),
    FAITHFUL_PRINT(12),
    READ_ONLY(0);

    private final int bit;

    UserAccessPermission(int bit){
        this.bit=bit;
    }
}

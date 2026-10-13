package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
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

    /**
     * The PDF permission bit this maps to.
     *
     * <p>Nothing reads it to build the permission set — {@code PdfTools.getUserAccessPermission}
     * matches on the constant itself — so it exists only to decode the numeric form below.
     */
    private final int bit;

    UserAccessPermission(int bit){
        this.bit=bit;
    }

    /**
     * Reads a permission from either its name or its PDF bit.
     *
     * <p>The bit form has to be accepted because the app sent it, and Jackson reads an integer as
     * an <em>ordinal</em>. The consequences were worse than a plain failure: bits 9 to 12 are out
     * of the legal index range and produced a 400, but 0, 3, 4, 5 and 6 are valid indices and
     * silently resolved to a <em>different</em> permission than the user picked — READ_ONLY became
     * PRINT, PRINT became MODIFY_ANNOTATIONS, and so on. A document was protected with
     * permissions nobody chose, with no error to show it. Matching on the bit makes the numeric
     * form mean what it says.
     *
     * @throws IllegalArgumentException with the accepted values, so a 400 can say what to send.
     */
    @JsonCreator
    public static UserAccessPermission from(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) {
            int bit = node.asInt();
            for (UserAccessPermission candidate : values()) {
                if (candidate.bit == bit) return candidate;
            }
            throw new IllegalArgumentException("Unknown permission bit " + bit + ".");
        }
        String text = node.asText("").trim();
        for (UserAccessPermission candidate : values()) {
            if (candidate.name().equalsIgnoreCase(text)) return candidate;
        }
        throw new IllegalArgumentException("Unknown permission '" + text + "'.");
    }
}

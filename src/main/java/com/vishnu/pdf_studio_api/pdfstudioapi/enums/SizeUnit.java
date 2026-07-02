package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

/**
 * How a tool's variable (size-based) credit component is measured.
 *
 * <ul>
 *   <li>{@link #NONE} — flat cost, no size component.</li>
 *   <li>{@link #BYTES} — extra credits per block of input bytes (cheap: read from the upload size).</li>
 *   <li>{@link #PAGES} — extra credits per block of PDF pages.</li>
 * </ul>
 */
public enum SizeUnit {
    NONE,
    BYTES,
    PAGES
}

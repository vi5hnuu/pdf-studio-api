package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

/**
 * How a tool's variable (size-based) credit component is measured.
 *
 * <ul>
 *   <li>{@link #NONE} — flat cost, no size component.</li>
 *   <li>{@link #BYTES} — extra credits per block of input bytes, read straight from the upload size.</li>
 * </ul>
 *
 * <p>A {@code PAGES} unit was declared here and in the schema but never implemented: the charging
 * aspect only ever supplied a byte count, so a {@code PAGES}-priced row would have billed bytes as
 * though they were pages — roughly a thousandfold overcharge on a typical document. It is removed
 * rather than left as a configuration option that silently misprices. Page-based pricing, if wanted,
 * needs the page count metered where the document is already open, not guessed before parsing.
 */
public enum SizeUnit {
    NONE,
    BYTES
}

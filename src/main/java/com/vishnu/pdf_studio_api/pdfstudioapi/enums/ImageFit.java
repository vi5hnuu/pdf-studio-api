package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

/**
 * How artwork is sized into the box a caller asked for.
 *
 * <p>Placement used to be unconditionally {@link #STRETCH}: {@code drawImage} was handed the box's
 * width and height with no reference to the artwork's own proportions, so a signature dropped into
 * a square box came out square. Clients that cared had to compute the right box themselves, and
 * only one of them did.
 */
public enum ImageFit {

    /**
     * Scale to the largest size that fits inside the box, keeping proportions, and centre it.
     *
     * <p>The default, because it is what a caller asking for "this image, about here, about this
     * big" almost always means.
     */
    CONTAIN,

    /** Fill the box exactly, distorting if the proportions differ. Only when explicitly asked. */
    STRETCH
}

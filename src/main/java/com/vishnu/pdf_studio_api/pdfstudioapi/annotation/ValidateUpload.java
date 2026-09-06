package com.vishnu.pdf_studio_api.pdfstudioapi.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares what an endpoint's uploaded parts must actually be, enforced by
 * {@code ValidateUploadAspect} before the controller body runs.
 *
 * <p>Follows the same declarative shape as {@link ChargeCredits}: the endpoint states its
 * expectation in one line and the aspect does the work, so 55 endpoints gain validation without
 * 55 copies of the same guard clause.
 *
 * <p>Examples:
 * <pre>
 *   &#64;ValidateUpload                                  // every file part must be a PDF
 *   &#64;ValidateUpload(minFiles = 2)                    // merge: at least two PDFs
 *   &#64;ValidateUpload(ValidateUpload.Kind.IMAGE)       // image-studio: every part is an image
 *   &#64;ValidateUpload(imageParts = "image")            // place-image: "file" is a PDF, "image" is not
 *   &#64;ValidateUpload(artworkParts = "stamp")          // stamp-pdf: "stamp" may be either
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidateUpload {

    enum Kind { PDF, IMAGE }

    /** Expected kind for every file part not listed in {@link #imageParts()}. */
    Kind value() default Kind.PDF;

    /**
     * Part names that are images regardless of {@link #value()} — for endpoints mixing both,
     * such as {@code place-image} ({@code file} is a PDF, {@code image} is not).
     */
    String[] imageParts() default {};

    /**
     * Part names that may be <b>either</b> a PDF or an image.
     *
     * <p>For artwork the distinction is the caller's choice rather than a constraint: a stamp is
     * equally validly a one-page PDF or a PNG logo. The tool asks {@link
     * com.vishnu.pdf_studio_api.pdfstudioapi.validation.UploadValidator#pdfOrImage} which it got
     * and draws it accordingly.
     */
    String[] artworkParts() default {};

    /** Minimum number of files a multi-file part must carry. Ignored for single-file parts. */
    int minFiles() default 1;
}

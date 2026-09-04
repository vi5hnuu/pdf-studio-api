package com.vishnu.pdf_studio_api.pdfstudioapi.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A failure with a deliberate HTTP status and a stable machine-readable code.
 *
 * <p>Tools throw this (rather than a bare {@code RuntimeException}) whenever the cause is
 * attributable to the request — an encrypted PDF, an out-of-range page, an unreadable upload — so
 * {@link GlobalExceptionHandler} can return an accurate 4xx instead of a blanket 500. Genuine server
 * faults stay unchecked and become a 500.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    // ── Common cases, named so call sites read as intent rather than status codes ──

    /** The upload is not the file type this tool accepts, or is corrupt beyond reading. */
    public static ApiException invalidFile(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE", message);
    }

    /** The PDF is password-protected and must be unlocked first. */
    public static ApiException encrypted() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF_ENCRYPTED",
                "This PDF is password-protected. Remove the password first, then try again.");
    }

    /** The request itself is well-formed but asks for something impossible (bad page range, etc.). */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    /** The input exceeds a configured processing limit (page count, file count, size). */
    public static ApiException tooLarge(String message) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", message);
    }
}

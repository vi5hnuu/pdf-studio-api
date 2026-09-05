package com.vishnu.pdf_studio_api.pdfstudioapi.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns every failure into the single {@link ApiError} envelope with an accurate status.
 *
 * <p>Before this existed, the tool services wrapped everything in {@code RuntimeException}, so an
 * encrypted PDF, a corrupt upload and a genuine server fault were indistinguishable 500s. Because
 * that wrapping is still the norm inside {@code PdfService}/{@code ImageService}, this handler
 * <b>unwraps the cause chain</b> ({@link #unwrap}) and classifies on the real cause — so existing
 * call sites get correct statuses without every one of them having to be rewritten.
 *
 * <p>Only 5xx responses log a stack trace, and never to the client: the response carries a
 * {@code traceId} that matches the log line.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** Deliberate, already-classified failures. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), ex, request);
    }

    /**
     * Raised by the credit system (402 insufficient credits, 409 already claimed/redeemed,
     * 429 daily cap) and by the load limiter (503). The status and reason are already correct.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        ResponseEntity<ApiError> response = build(status, codeFor(status), message, ex, request);

        // Preserve headers the thrower attached — notably Retry-After on the load limiter's 503,
        // which tells a well-behaved client exactly when to come back.
        if (!ex.getHeaders().isEmpty()) {
            return ResponseEntity.status(status).headers(ex.getHeaders()).body(response.getBody());
        }
        return response;
    }

    // ── Request-shape problems ────────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                details.isBlank() ? "Request validation failed." : details, ex, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex, HttpServletRequest request) {
        String details = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                details.isBlank() ? "Request validation failed." : details, ex, request);
    }

    @ExceptionHandler({
            MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiError> handleMalformed(Exception ex, HttpServletRequest request) {
        String message = switch (ex) {
            case MissingServletRequestPartException e ->
                    "Required file part '" + e.getRequestPartName() + "' is missing.";
            case MissingServletRequestParameterException e ->
                    "Required parameter '" + e.getParameterName() + "' is missing.";
            default -> "The request body could not be read. Check the field types and try again.";
        };
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", message, ex, request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "This endpoint expects a multipart/form-data upload.", ex, request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                "That file is larger than the upload limit. Try a smaller file.", ex, request);
    }

    /**
     * Catch-all. Classifies on the unwrapped cause so the tool services' blanket
     * {@code RuntimeException} wrapping still yields an accurate status.
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiError> handleAny(Throwable ex, HttpServletRequest request) {
        Throwable cause = unwrap(ex);

        if (cause instanceof ApiException api) {
            return build(api.getStatus(), api.getCode(), api.getMessage(), ex, request);
        }
        if (cause instanceof ResponseStatusException rse) {
            return handleResponseStatus(rse, request);
        }
        if (cause instanceof InvalidPasswordException) {
            ApiException enc = ApiException.encrypted();
            return build(enc.getStatus(), enc.getCode(), enc.getMessage(), ex, request);
        }
        if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException) {
            String message = cause.getMessage();
            return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                    (message == null || message.isBlank()) ? "Invalid request." : message, ex, request);
        }
        if (cause instanceof IOException) {
            // PDFBox/ImageIO surface unreadable or truncated input as IOException — that is the
            // caller's file being unusable, not this service failing.
            return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE",
                    "This file could not be read. It may be corrupt or not a supported format.", ex, request);
        }
        if (cause instanceof OutOfMemoryError) {
            return build(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "This file is too complex to process. Try a smaller or simpler file.", ex, request);
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong on our side. Please try again.", ex, request);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /**
     * Walks the cause chain to the most specific meaningful throwable.
     *
     * <p>The tool services wrap failures as {@code new RuntimeException(e)}, sometimes more than once,
     * so the useful type is buried. Returns the deepest cause that is not a plain wrapper; falls back
     * to the original when the chain carries no extra information.
     */
    private Throwable unwrap(Throwable ex) {
        Throwable current = ex;
        Throwable best = ex;
        int guard = 0; // cycle-safe
        while (current != null && guard++ < 10) {
            if (isMeaningful(current)) best = current;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return best;
    }

    /** True for types that tell us something a generic wrapper does not. */
    private boolean isMeaningful(Throwable t) {
        return t instanceof ApiException
                || t instanceof ResponseStatusException
                || t instanceof InvalidPasswordException
                || t instanceof IllegalArgumentException
                || t instanceof IllegalStateException
                || t instanceof IOException;
    }

    /** Stable client-facing code for statuses raised without one (credits, limiter). */
    private String codeFor(HttpStatus status) {
        return switch (status) {
            case PAYMENT_REQUIRED -> "INSUFFICIENT_CREDITS";
            case CONFLICT -> "ALREADY_CLAIMED";
            case TOO_MANY_REQUESTS -> "RATE_LIMITED";
            case SERVICE_UNAVAILABLE -> "SERVER_BUSY";
            case NOT_FOUND -> "NOT_FOUND";
            case FORBIDDEN -> "FORBIDDEN";
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case PAYLOAD_TOO_LARGE -> "FILE_TOO_LARGE";
            default -> status.is4xxClientError() ? "BAD_REQUEST" : "INTERNAL_ERROR";
        };
    }

    /** Builds the envelope, logging 5xx with a stack trace and 4xx as a one-line warning. */
    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
                                           Throwable ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        String path = request != null ? request.getRequestURI() : "-";
        if (status.is5xxServerError()) {
            log.error("[{}] {} {} -> {} {}", traceId, request != null ? request.getMethod() : "-", path,
                    status.value(), code, ex);
        } else {
            log.warn("[{}] {} {} -> {} {}: {}", traceId, request != null ? request.getMethod() : "-", path,
                    status.value(), code, ex.getMessage());
        }
        return ResponseEntity.status(status).body(ApiError.of(status.value(), code, message, traceId));
    }
}

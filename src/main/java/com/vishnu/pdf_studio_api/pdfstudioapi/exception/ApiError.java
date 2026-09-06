package com.vishnu.pdf_studio_api.pdfstudioapi.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * The single error envelope every failed request returns.
 *
 * <p>Mirrors the success shape used by the credit endpoints ({@code {"success": true, "data": …}})
 * so clients can branch on one field. {@link #code} is a stable, machine-readable token the apps
 * switch on ({@code INSUFFICIENT_CREDITS}, {@code FILE_TOO_LARGE}, …) — never parse {@link #message},
 * which is human-facing and may be reworded.
 *
 * <p>{@link #traceId} correlates the response with the server log entry; the stack trace itself is
 * never sent to the client.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        boolean success,
        int status,
        String code,
        String message,
        String traceId,
        Instant timestamp
) {
    public static ApiError of(int status, String code, String message, String traceId) {
        return new ApiError(false, status, code, message, traceId, Instant.now());
    }
}

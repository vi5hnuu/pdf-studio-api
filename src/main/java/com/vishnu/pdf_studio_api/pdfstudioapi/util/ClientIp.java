package com.vishnu.pdf_studio_api.pdfstudioapi.util;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * Resolves the real client IP. {@code X-Forwarded-For} is only trusted when the direct
 * connection is from a private/loopback address (a reverse proxy we control), otherwise
 * the header is spoofable and ignored.
 */
public final class ClientIp {

    private static final Pattern PRIVATE_IP = Pattern.compile(
            "^(127\\.|10\\.|172\\.(1[6-9]|2\\d|3[01])\\.|192\\.168\\.|::1$|0:0:0:0:0:0:0:1$)");

    private ClientIp() {}

    public static String of(HttpServletRequest request) {
        final String remoteAddr = request.getRemoteAddr();
        final String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank() && PRIVATE_IP.matcher(remoteAddr).find()) {
            final String candidate = forwarded.split(",")[0].trim();
            if (!candidate.isEmpty() && !PRIVATE_IP.matcher(candidate).find()) {
                return candidate;
            }
        }
        return remoteAddr;
    }
}

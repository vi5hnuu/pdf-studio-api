package com.vishnu.pdf_studio_api.pdfstudioapi.security;

import java.util.regex.Pattern;

/**
 * Validation for an auth subject the service will trust as an identifier.
 *
 * <p>Not every string in a token is safe to use as an account key — one subject travels in from
 * outside a verified token (the obfuscated external account id a Play purchase carries, attached
 * by the client) and from there into database columns, server-to-server URL paths, and log lines.
 * Refusing anything outside the shape auth actually issues — rather than escaping at each use —
 * keeps all three unambiguous and means a new call site cannot reintroduce the problem.
 */
public final class Subjects {

    /**
     * The shape of a subject this service will key an account by.
     *
     * <p>Auth issues 26-character Crockford base32 ids and the column holding them is 64 wide;
     * 64 is also Play's limit on an obfuscated external account id, so nothing legitimate is
     * excluded.
     */
    private static final Pattern SAFE_SUBJECT = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private Subjects() {}

    public static boolean isValid(String subject) {
        return subject != null && SAFE_SUBJECT.matcher(subject).matches();
    }
}

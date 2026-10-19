package com.vishnu.pdf_studio_api.pdfstudioapi.security;

import java.util.regex.Pattern;

/**
 * The identity every per-user record in this service is keyed by: {@code alias:sub}.
 *
 * <p>{@code sub} alone is not unique across auth instances — the app's and the web's auth
 * deployments have separate databases and generate ids independently — so a bare {@code sub} would
 * eventually let two different people share a credit balance.
 */
public final class PrincipalKey {

    /** Separator chosen because it cannot appear in a UUID or in an issuer alias. */
    public static final String SEPARATOR = ":";

    /**
     * The shape of a subject this service will key an account by.
     *
     * <p>Auth issues 26-character Crockford base32 ids, and the column holding them is 40 wide;
     * 64 is Play's limit on an obfuscated external account id, so nothing legitimate is excluded.
     */
    private static final Pattern SAFE_SUBJECT = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private PrincipalKey() {}

    /**
     * Whether a subject may be trusted as an identifier.
     *
     * <p>Needed because one subject does not come from a verified token: the obfuscated external
     * account id on a Play purchase is attached by the client, and flows from there into an
     * account key, into the path of a server-to-server URL and into log lines. Refusing anything
     * outside the shape auth actually issues — rather than escaping it at each use — keeps all
     * three unambiguous and means a new use site cannot reintroduce the problem.
     */
    public static boolean isValidSubject(String subject) {
        return subject != null && SAFE_SUBJECT.matcher(subject).matches();
    }

    public static String of(String alias, String subject) {
        return alias + SEPARATOR + subject;
    }

    /** The issuer alias portion, or null if the key is not namespaced (pre-migration data). */
    public static String aliasOf(String principalKey) {
        if (principalKey == null) return null;
        int idx = principalKey.indexOf(SEPARATOR);
        return idx > 0 ? principalKey.substring(0, idx) : null;
    }

    /** The auth-service subject portion. */
    public static String subjectOf(String principalKey) {
        if (principalKey == null) return null;
        int idx = principalKey.indexOf(SEPARATOR);
        return idx >= 0 ? principalKey.substring(idx + 1) : principalKey;
    }
}

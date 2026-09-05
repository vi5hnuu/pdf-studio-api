package com.vishnu.pdf_studio_api.pdfstudioapi.security;

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

    private PrincipalKey() {}

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

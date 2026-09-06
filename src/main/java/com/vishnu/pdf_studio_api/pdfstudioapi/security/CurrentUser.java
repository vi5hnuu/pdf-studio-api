package com.vishnu.pdf_studio_api.pdfstudioapi.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resolves the authenticated caller's auth {@code userId} (the JWT subject) from the
 * security context. The credit system keys every per-user record by this value.
 */
public final class CurrentUser {

    private CurrentUser() {}

    /** @return the current userId, or {@code null} when the request is unauthenticated. */
    public static String id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        return auth.getName(); // principalClaimName = "sub" (see SecurityConfig)
    }

    public static String requireId() {
        String id = id();
        if (id == null) {
            throw new IllegalStateException("No authenticated user in context");
        }
        return id;
    }
}

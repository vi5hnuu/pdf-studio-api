package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code app.auth.*} — the one auth-service instance whose tokens this service accepts.
 *
 * <p>The mobile app and the web tier used to be served by separate auth deployments with separate
 * databases, so every per-user record was keyed by {@code alias:subject} to keep their id spaces
 * from colliding. The deployments have since been collapsed into one, which makes the subject
 * unique on its own; a single-issuer configuration is both correct now and what prevents the
 * same user showing up as two balances again.
 */
@Component
@ConfigurationProperties(prefix = "app.auth")
@Getter
@Setter
public class AuthIssuerProperties {

    /** The audience claim a token must carry to be accepted here — this product's id. */
    private String expectedAudience = "pdf-studio-api";

    /** Exact {@code iss} claim value tokens from the auth service carry. */
    private String issuer;

    /** Where the auth service publishes its signing keys. */
    private String jwksUri;

    /**
     * Base URL for server-to-server calls to the auth service, e.g. marking a guest as retained.
     * Distinct from {@link #issuer}, which is a claim value and need not be reachable, and from
     * {@link #jwksUri}, which is a single document. Blank disables the calls.
     */
    private String internalBaseUrl;
}

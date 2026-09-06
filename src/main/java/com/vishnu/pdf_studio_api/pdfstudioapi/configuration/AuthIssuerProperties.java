package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds {@code app.auth.*} — the auth-service instances whose tokens this service accepts.
 *
 * <p>There is more than one because the mobile app and the web tier are served by separate auth
 * deployments with separate databases (see the auth repo's {@code docs/DOCKER.md}). Each signs with
 * its own key and declares its own {@code iss}, so each needs its own JWKS and validator.
 *
 * <p>Each issuer carries an {@link Issuer#alias}. Because the two instances generate user ids
 * independently, the same id can legitimately exist in both; every per-user record here is
 * therefore keyed by {@code alias:sub} rather than {@code sub} alone, so two unrelated people can
 * never end up sharing one credit balance.
 */
@Component
@ConfigurationProperties(prefix = "app.auth")
@Getter
@Setter
public class AuthIssuerProperties {

    /** The audience claim a token must carry to be accepted here — this product's id. */
    private String expectedAudience = "pdf-studio-api";

    private List<Issuer> issuers = new ArrayList<>();

    @Getter
    @Setter
    public static class Issuer {

        /**
         * Short, stable namespace prefix for user ids from this issuer (e.g. {@code app}, {@code web}).
         * Changing it after launch orphans every credit account minted under the old value.
         */
        private String alias;

        /** Exact {@code iss} claim value tokens from this instance carry. */
        private String issuer;

        /** Where this instance publishes its signing keys. */
        private String jwksUri;
    }
}

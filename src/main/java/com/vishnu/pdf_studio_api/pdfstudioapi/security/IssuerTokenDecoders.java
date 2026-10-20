package com.vishnu.pdf_studio_api.pdfstudioapi.security;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.AuthIssuerProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Decoder for the trusted auth issuer, and the subject a raw token maps to.
 *
 * <p>The security filter chain validates the token on the request. This exists for the one case
 * that needs to validate a <em>second</em> token inside a request: carrying a guest's credits onto
 * the account they just signed in to, where the request is authenticated as the new account and
 * the guest token arrives in the body.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IssuerTokenDecoders {

    private final AuthIssuerProperties properties;
    private JwtDecoder decoder;

    @PostConstruct
    void build() {
        this.decoder = buildDecoder();
    }

    private JwtDecoder buildDecoder() {
        NimbusJwtDecoder jwt = NimbusJwtDecoder.withJwkSetUri(properties.getJwksUri()).build();
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(properties.getExpectedAudience()));
        jwt.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(properties.getIssuer()),
                audience));
        return jwt;
    }

    /**
     * Validates a raw access token.
     *
     * @return the bare JWT subject, or empty when the token is not accepted
     */
    public Optional<String> subjectOf(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            Jwt jwt = decoder.decode(token);
            return Optional.of(jwt.getSubject());
        } catch (JwtException notAccepted) {
            log.debug("Token was not accepted by the configured issuer");
            return Optional.empty();
        }
    }
}

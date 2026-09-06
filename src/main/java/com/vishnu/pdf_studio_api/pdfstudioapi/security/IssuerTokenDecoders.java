package com.vishnu.pdf_studio_api.pdfstudioapi.security;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.AuthIssuerProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decoders for every trusted auth issuer, and the namespaced principal a raw token maps to.
 *
 * <p>The security filter chain validates the token on the request. This exists for the one
 * case that needs to validate a <em>second</em> token inside a request: carrying a guest's
 * credits onto the account they just signed in to, where the request is authenticated as the
 * new account and the guest token arrives in the body.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IssuerTokenDecoders {

    private final AuthIssuerProperties properties;
    private final Map<String, JwtDecoder> byIssuer = new LinkedHashMap<>();
    private final Map<String, String> aliasByIssuer = new LinkedHashMap<>();

    @PostConstruct
    void build() {
        for (AuthIssuerProperties.Issuer issuer : properties.getIssuers()) {
            byIssuer.put(issuer.getIssuer(), decoderFor(issuer));
            aliasByIssuer.put(issuer.getIssuer(), issuer.getAlias());
        }
    }

    /** A decoder enforcing this issuer's signature, issuer claim, expiry and our audience. */
    public JwtDecoder decoderFor(AuthIssuerProperties.Issuer issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuer.getJwksUri()).build();
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(properties.getExpectedAudience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(issuer.getIssuer()),
                audience));
        return decoder;
    }

    /**
     * Validates a raw access token against every trusted issuer.
     *
     * @return the namespaced principal ({@code alias:sub}), or empty when no issuer accepts it
     */
    public Optional<String> principalKeyOf(String token) {
        if (token == null || token.isBlank()) return Optional.empty();

        for (var entry : byIssuer.entrySet()) {
            try {
                Jwt jwt = entry.getValue().decode(token);
                String alias = aliasByIssuer.get(entry.getKey());
                return Optional.of(PrincipalKey.of(alias, jwt.getSubject()));
            } catch (JwtException notThisIssuer) {
                // Expected while trying each issuer in turn; only the last failure matters.
            }
        }
        log.debug("Token was not accepted by any configured issuer");
        return Optional.empty();
    }
}

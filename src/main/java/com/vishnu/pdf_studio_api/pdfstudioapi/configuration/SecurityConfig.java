package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;

import java.util.List;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource-server security. This service holds no auth code: it validates the RS256
 * access tokens minted by the standalone auth service against that service's published
 * JWKS, additionally checking the {@code iss} claim and expiry. The authenticated
 * principal's name is the auth {@code userId} (JWT subject), which the credit system
 * keys all per-user data by.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Public paths — docs, health, and the machine-to-machine Play RTDN webhook (secret-guarded). */
    private static final String[] PUBLIC_PATHS = {
            "/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
            "/actuator/health",
            "/api/v1/credits/play-rtdn",
    };

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Value("${app.auth.expected-issuer}")
    private String expectedIssuer;

    @Value("${app.auth.expected-audience}")
    private String expectedAudience;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /** Decodes + verifies tokens against the auth service's JWKS and enforces the issuer. */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        // Reject a token unless its aud claim contains THIS product's id — so a token
        // minted for another product (or with no audience) can't be replayed here.
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(expectedAudience));
        OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(expectedIssuer),
                audienceValidator);
        decoder.setJwtValidator(validators);
        return decoder;
    }

    /** Maps the token's {@code roles} claim (e.g. ["ROLE_USER"]) to Spring authorities. */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix(""); // roles already carry the ROLE_ prefix

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        // Principal name = subject = auth userId.
        converter.setPrincipalClaimName("sub");
        return converter;
    }
}

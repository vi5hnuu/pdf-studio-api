package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import com.vishnu.pdf_studio_api.pdfstudioapi.security.NamespacedJwtAuthenticationConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resource-server security. This service holds no auth code: it validates the RS256 access tokens
 * minted by the auth service(s) against their published JWKS, checking issuer, audience and expiry.
 *
 * <p><b>Multiple issuers.</b> The mobile app and the web tier are served by separate auth
 * deployments — separate databases, separate signing keys, separate {@code iss} values — so a single
 * decoder is not enough. Each configured issuer gets its own decoder and its own namespace alias,
 * and {@link JwtIssuerAuthenticationManagerResolver} routes a token to the right one by its
 * {@code iss} claim. A token whose issuer is not configured is rejected outright.
 *
 * <p>The authenticated principal's name is {@code alias:sub}, which the credit system keys all
 * per-user data by — see {@link com.vishnu.pdf_studio_api.pdfstudioapi.security.PrincipalKey}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    /** Public paths — docs and health. (The RTDN webhook has its own chain below.) */
    private static final String[] PUBLIC_PATHS = {
            "/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
            "/actuator/health",
    };

    /** Path of the machine-to-machine Play RTDN webhook. */
    private static final String RTDN_PATH = "/api/v1/credits/play-rtdn";

    private final AuthIssuerProperties authProperties;

    /**
     * Dedicated chain for the RTDN webhook. It deliberately does NOT enable the OAuth2 resource
     * server, so the Pub/Sub OIDC token in the {@code Authorization} header is left untouched (it is
     * Google-signed, not from our auth JWKS, and would otherwise be rejected with 401 before
     * reaching the controller). The controller authenticates it itself (OIDC + shared secret).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain webhookFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(RTDN_PATH)
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           AuthenticationManagerResolver<HttpServletRequest> resolver)
            throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.authenticationManagerResolver(resolver))
                .build();
    }

    /**
     * Routes each token to the decoder for its {@code iss}.
     *
     * <p>Built explicitly rather than with {@code fromTrustedIssuers} because that form performs
     * OIDC discovery against the issuer URL; our auth service publishes a plain JWKS document and no
     * discovery metadata, so each issuer's JWKS URI is configured directly.
     */
    @Bean
    public AuthenticationManagerResolver<HttpServletRequest> jwtIssuerResolver() {
        List<AuthIssuerProperties.Issuer> issuers = authProperties.getIssuers();
        if (issuers.isEmpty()) {
            throw new IllegalStateException(
                    "No app.auth.issuers configured — this service cannot authenticate anyone.");
        }

        Map<String, AuthenticationManager> managers = new LinkedHashMap<>();
        for (AuthIssuerProperties.Issuer issuer : issuers) {
            validate(issuer);
            JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoderFor(issuer));
            provider.setJwtAuthenticationConverter(
                    new NamespacedJwtAuthenticationConverter(issuer.getAlias()));
            managers.put(issuer.getIssuer(), new ProviderManager(provider));
            log.info("Trusting auth issuer '{}' (alias '{}') via {}",
                    issuer.getIssuer(), issuer.getAlias(), issuer.getJwksUri());
        }
        // An unknown issuer resolves to no manager, which Spring Security turns into a 401.
        return new JwtIssuerAuthenticationManagerResolver(managers::get);
    }

    /** Decoder for one issuer: its JWKS, its exact issuer claim, and this product's audience. */
    private JwtDecoder decoderFor(AuthIssuerProperties.Issuer issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuer.getJwksUri()).build();

        // Reject a token unless its aud claim contains THIS product's id, so a token minted for a
        // different product cannot be replayed here.
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(authProperties.getExpectedAudience()));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(issuer.getIssuer()),
                audience));
        return decoder;
    }

    /**
     * Fails fast on a half-configured issuer. A blank alias would namespace every account under
     * {@code ":"}, silently merging the user spaces this separation exists to keep apart.
     */
    private void validate(AuthIssuerProperties.Issuer issuer) {
        if (isBlank(issuer.getAlias()) || isBlank(issuer.getIssuer()) || isBlank(issuer.getJwksUri())) {
            throw new IllegalStateException(
                    "Incomplete app.auth.issuers entry — alias, issuer and jwks-uri are all required. Got: "
                            + issuer.getAlias() + " / " + issuer.getIssuer() + " / " + issuer.getJwksUri());
        }
        if (issuer.getAlias().contains(":")) {
            throw new IllegalStateException(
                    "Issuer alias must not contain ':' (it is the namespace separator): " + issuer.getAlias());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

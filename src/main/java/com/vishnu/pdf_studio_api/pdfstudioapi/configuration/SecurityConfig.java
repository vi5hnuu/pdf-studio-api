package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import com.vishnu.pdf_studio_api.pdfstudioapi.security.SubjectJwtAuthenticationConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Resource-server security. This service holds no auth code: it validates the RS256 access tokens
 * minted by the auth service against its published JWKS, checking issuer, audience and expiry.
 *
 * <p>The authenticated principal's name is the bare JWT subject, which the credit system keys
 * every per-user record by.
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
            // Read before the app has a token, and on purpose: if an auth change is why a build
            // has to be forced out, needing auth to find that out is circular.
            "/api/v1/app-config",
    };

    /** Path of the machine-to-machine Play RTDN webhook. */
    private static final String RTDN_PATH = "/api/v1/credits/play-rtdn";

    /**
     * Path of AdMob's rewarded server-side verification callback. Google signs the query string
     * rather than sending a bearer token, so the resource server must not see it either.
     */
    private static final String ADMOB_SSV_PATH = "/api/v1/credits/admob-ssv";

    /**
     * Path of the Razorpay webhook. Razorpay signs the raw body with its webhook secret
     * ({@code X-Razorpay-Signature}), not a JWT, so the resource server's bearer filter must
     * not see it — otherwise it would be rejected before the controller could verify the HMAC.
     */
    private static final String RAZORPAY_WEBHOOK_PATH = "/api/v1/credits/razorpay/webhook";

    private final AuthIssuerProperties authProperties;

    /**
     * Dedicated chain for the machine-to-machine webhooks (Play RTDN and AdMob SSV). It
     * deliberately does NOT enable the OAuth2 resource server, so the Pub/Sub OIDC token in the
     * {@code Authorization} header is left untouched (it is Google-signed, not from our auth JWKS,
     * and would otherwise be rejected with 401 before reaching the controller). The controller
     * authenticates it itself (OIDC + shared secret).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain webhookFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(RTDN_PATH, ADMOB_SSV_PATH, RAZORPAY_WEBHOOK_PATH)
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder decoder) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
                        .decoder(decoder)
                        .jwtAuthenticationConverter(new SubjectJwtAuthenticationConverter())))
                .build();
    }

    /** Decoder for the configured issuer: its JWKS, its exact issuer claim, and this product's audience. */
    @Bean
    public JwtDecoder jwtDecoder() {
        validate();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(authProperties.getJwksUri()).build();

        // Reject a token unless its aud claim contains THIS product's id, so a token minted for a
        // different product cannot be replayed here.
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(authProperties.getExpectedAudience()));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(authProperties.getIssuer()),
                audience));

        log.info("Trusting auth issuer '{}' via {}", authProperties.getIssuer(), authProperties.getJwksUri());
        return decoder;
    }

    private void validate() {
        if (isBlank(authProperties.getIssuer()) || isBlank(authProperties.getJwksUri())) {
            throw new IllegalStateException(
                    "Incomplete app.auth configuration — issuer and jwks-uri are both required. Got: "
                            + authProperties.getIssuer() + " / " + authProperties.getJwksUri());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

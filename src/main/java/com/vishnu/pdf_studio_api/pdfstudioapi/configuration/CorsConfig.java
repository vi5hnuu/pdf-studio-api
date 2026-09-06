package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import lombok.NonNull;
import org.springframework.http.HttpHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
@RequiredArgsConstructor
public class CorsConfig implements WebMvcConfigurer {

    private final Environment environment;

    /**
     * Browser origins allowed to call this API, comma-separated.
     *
     * <p>Previously hardcoded per profile, so adding a preview deployment, a second domain or
     * a different local port meant editing and redeploying the service.
     */
    @Value("${app.cors.allowed-origins:}")
    private String configuredOrigins;

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {

        String[] allowedOrigins;

        // An explicit list always wins, so a deployment can add an origin without a code change.
        if (configuredOrigins != null && !configuredOrigins.isBlank()) {
            allowedOrigins = Arrays.stream(configuredOrigins.split(","))
                    .map(String::trim).filter(o -> !o.isEmpty()).toArray(String[]::new);
        }
        // Otherwise: localhost when running locally, the product domains in "prod".
        else if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            allowedOrigins = new String[]{
                    "https://pdf-studio.laxmi.solutions",
                    "https://pdf-studio-vi.onrender.com",
                    "http://pdf-craft.laxmi.solutions:8082"
            };
        } else {
            allowedOrigins = new String[]{
                    "http://localhost:3000",
                    "http://localhost:3001",
            };
        }

        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                // A browser hands JavaScript only the handful of "simple" response headers
                // unless they are listed here. The web app and the API are on different
                // origins, so without this every download fell back to a generic filename
                // instead of the one the user chose, and the credit headers were unreadable.
                .exposedHeaders(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "X-Credits-Charged",
                        "X-Credits-Remaining",
                        "X-Pages-Removed",
                        HttpHeaders.RETRY_AFTER)
                .allowCredentials(false)
                .maxAge(3600);
    }
}

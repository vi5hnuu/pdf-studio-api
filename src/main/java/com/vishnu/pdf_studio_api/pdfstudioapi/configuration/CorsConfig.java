package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import lombok.NonNull;
import org.springframework.http.HttpHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
@RequiredArgsConstructor
public class CorsConfig implements WebMvcConfigurer {

    private final Environment environment;

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {

        String[] allowedOrigins;

        // Allow localhost when running locally (no profile or explicit "dev"); restrict to prod domains only in "prod"
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            allowedOrigins = new String[]{
                    "https://pdf-studio.laxmi.solutions",
                    "https://pdf-studio-vi.onrender.com"
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

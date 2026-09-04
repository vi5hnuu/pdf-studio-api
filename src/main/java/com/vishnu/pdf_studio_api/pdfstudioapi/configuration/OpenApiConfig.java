package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the bearer-token scheme so Swagger UI can actually call these endpoints.
 *
 * <p>Every route except the docs, health and the RTDN webhook requires a JWT from the auth service.
 * Without a declared scheme the "Authorize" button never appears and every try-it-out returns 401,
 * which made the published docs untestable.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI pdfStudioOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PDF Studio API")
                        .version("v1")
                        .description("""
                                PDF and image tooling. All endpoints require an RS256 access token \
                                issued by the auth service, sent as `Authorization: Bearer <token>`.

                                Paid tools debit the caller's credit balance on success. Send an \
                                `Idempotency-Key` header so a retried request is never charged twice."""))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token from the auth service (the `sub` claim is the userId).")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}

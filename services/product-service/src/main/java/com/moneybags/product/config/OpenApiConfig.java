package com.moneybags.product.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI productServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MoneyBags Product Service API")
                        .description("Product catalogue, immutable product history, charges, and rules. Authenticate Swagger requests with the session ID returned by POST /api/v1/auth/login.")
                        .version("v1"))
                .components(new Components().addSecuritySchemes("sessionId", sessionIdScheme()))
                .addSecurityItem(new SecurityRequirement().addList("sessionId"));
    }

    private SecurityScheme sessionIdScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-Session-Id")
                .description("Raw sessionId returned by POST /api/v1/auth/login");
    }
}

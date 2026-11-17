package com.expensetracker.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI arthOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Arth API").version("0.1.0")
                        .description("Personal finance platform for India. Authenticate via POST /api/auth/login (sets an httpOnly cookie)."))
                .components(new Components()
                        .addSecuritySchemes("session", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE).name("arth_session"))
                        .addSecuritySchemes("ingestToken", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Ingest-Token")));
    }
}

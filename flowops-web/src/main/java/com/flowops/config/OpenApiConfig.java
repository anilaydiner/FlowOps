package com.flowops.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "BearerAuth";

    @Bean
    public OpenAPI flowOpsOpenAPI() {
        return new OpenAPI()
            .info(new Info().title("FlowOps API")
                .description("Request & Approval Platform — manage users, permissions, "
                        + "departments, and approval workflows.")
                .version("v1")
                .contact(new Contact().name("FlowOps Team").email("flowops@example.com"))
                .license(new License().name("Internal Use")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH))
            .components(new Components().addSecuritySchemes(BEARER_AUTH,
                    new SecurityScheme().name(BEARER_AUTH)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Provide a JWT obtained from POST /api/v1/auth/token")));
    }

}

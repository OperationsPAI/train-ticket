package edu.fudan.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Common OpenAPI configuration for all Train Ticket microservices.
 * This provides a unified API documentation style across all services.
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name:train-ticket-service}")
    private String applicationName;

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title(formatTitle(applicationName))
                        .version("1.0.0")
                        .description(generateDescription(applicationName))
                        .contact(new Contact()
                                .name("Train Ticket Development Team")
                                .email("support@trainticket.com")
                                .url("https://github.com/FudanSELab/train-ticket"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort)
                                .description("Local development server")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT Bearer token authentication")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    private String formatTitle(String name) {
        if (name == null || name.isEmpty()) {
            return "Train Ticket API";
        }
        // Convert ts-xxx-service to Title Case
        return name.replace("ts-", "")
                .replace("-service", "")
                .replace("-", " ")
                .substring(0, 1).toUpperCase() 
                + name.replace("ts-", "")
                .replace("-service", "")
                .replace("-", " ")
                .substring(1) 
                + " Service API";
    }

    private String generateDescription(String name) {
        return "Train Ticket System - " + formatTitle(name) + "\n\n" +
                "This is part of the Train Ticket microservices system, " +
                "a benchmark microservice system for research and education.\n\n" +
                "## Authentication\n" +
                "Most endpoints require JWT Bearer token authentication. " +
                "Include the token in the Authorization header as: `Bearer <token>`\n\n" +
                "## Response Format\n" +
                "All responses follow a standard format:\n" +
                "```json\n" +
                "{\n" +
                "  \"status\": 1,  // 1 for success, 0 for failure\n" +
                "  \"msg\": \"Operation result message\",\n" +
                "  \"data\": {}   // Response payload\n" +
                "}\n" +
                "```";
    }
}

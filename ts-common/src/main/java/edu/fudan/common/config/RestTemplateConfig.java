package edu.fudan.common.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Common RestTemplate configuration that automatically handles JWT propagation.
 * 
 * Services can either:
 * 1. Import this configuration class: @Import(RestTemplateConfig.class)
 * 2. Or use component scanning if ts-common is scanned
 * 
 * The configured RestTemplate will automatically:
 * - Propagate JWT Authorization headers from incoming requests
 * - Avoid Content-Length mismatch issues in Spring Boot 3.x
 * 
 * @author fdse
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .interceptors(new JwtInterceptor())
                .build();
    }
}

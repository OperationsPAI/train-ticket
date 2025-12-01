package edu.fudan.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author fdse
 */
@Configuration
public class SwaggerConfig {

    @Value("${springdoc.packages-to-scan:}")
    private String packagesToScan;

    private static final Logger LOGGER = LoggerFactory.getLogger(SwaggerConfig.class);

    @Bean
    public OpenAPI customOpenAPI() {
        LOGGER.info("[customOpenAPI][create][packagesToScan: {}]", packagesToScan);
        return new OpenAPI()
                .info(new Info()
                        .title("Train Ticket API Documentation")
                        .description("Simple and elegant restful style - Built with SpringDoc OpenAPI")
                        .version("1.0")
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://github.com/FudanSELab/train-ticket")));
    }

}

package com.trainticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import edu.fudan.common.config.RestTemplateConfig;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * @author fdse
 */
@OpenAPIDefinition(info = @Info(
    title = "Payment Service API",
    version = "1.0",
    description = "支付服务 API"
))
@SpringBootApplication
@Import(RestTemplateConfig.class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableAsync
@IntegrationComponentScan
public class PaymentApplication {

    private PaymentApplication() {
        // Private constructor to prevent instantiation
    }

	public static void main(String[] args) {
		SpringApplication.run(PaymentApplication.class, args);
	}
}

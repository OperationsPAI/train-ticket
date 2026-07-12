package com.trainticket.postsales.adapters.http;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

/**
 * These services do not ship Boot's RestClient auto-configuration, so the
 * container-managed builder is provided here. Outbound clients must inject
 * this bean rather than call RestClient.builder() directly: java-kit's
 * trace-propagation post-processor only sees container-managed builders.
 */
@Configuration(proxyBeanMethods = false)
class HttpClientConfiguration {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}

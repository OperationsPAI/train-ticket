package com.trainticket.journeyorder.infrastructure.identity;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

/**
 * These services do not ship Boot's RestClient auto-configuration, so the
 * container-managed builder is provided here. Outbound clients must inject this
 * bean rather than call RestClient.builder() directly: java-kit's
 * trace-propagation post-processor only sees container-managed builders, so a
 * directly constructed builder would silently omit the outbound W3C traceparent.
 *
 * Prototype-scoped because callers mutate the builder (base URL, request
 * factory) before building; a singleton would leak one client's base URL into
 * the next.
 */
@Configuration(proxyBeanMethods = false)
class HttpClientConfiguration {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}

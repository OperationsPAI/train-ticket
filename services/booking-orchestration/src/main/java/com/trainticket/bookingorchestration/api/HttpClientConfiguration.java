package com.trainticket.bookingorchestration.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfiguration {
    @Bean
    @Scope("prototype")
    RestClient.Builder restClientBuilder() { return RestClient.builder(); }
}

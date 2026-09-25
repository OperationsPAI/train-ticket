package com.trainticket.postsales.observability;

import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds the compensation outcome recorder to the process meter when one exists. */
@Configuration(proxyBeanMethods = false)
public class PostSalesObservabilityConfiguration {
    @Bean
    @ConditionalOnMissingBean
    PostSalesMetricRecorder postSalesMetricRecorder(ObjectProvider<OpenTelemetry> openTelemetry) {
        OpenTelemetry sdk = openTelemetry.getIfAvailable();
        return sdk == null
            ? NoopPostSalesMetrics.INSTANCE
            : new OpenTelemetryPostSalesMetrics(sdk.getMeter("post-sales"));
    }
}

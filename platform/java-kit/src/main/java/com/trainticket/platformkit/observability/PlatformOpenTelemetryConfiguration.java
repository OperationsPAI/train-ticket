package com.trainticket.platformkit.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Conditional(OtelEnabledCondition.class)
public class PlatformOpenTelemetryConfiguration {
    @Bean
    @ConditionalOnMissingBean
    SpanExporterFactory spanExporterFactory() {
        return new OtlpSpanExporterFactory();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SpanExporter spanExporter(Environment environment, SpanExporterFactory exporterFactory) {
        return exporterFactory.create(OtelProperties.otlpEndpoint(environment));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SdkTracerProvider sdkTracerProvider(Environment environment, SpanExporter spanExporter) {
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
            AttributeKey.stringKey("service.name"),
            OtelProperties.serviceName(environment)
        )));
        return SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
            .build();
    }

    @Bean
    @ConditionalOnMissingBean
    OpenTelemetry openTelemetry(SdkTracerProvider tracerProvider) {
        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    }

    @Bean
    GlobalOpenTelemetryRegistrar globalOpenTelemetryRegistrar(ObjectProvider<OpenTelemetry> openTelemetry) {
        return new GlobalOpenTelemetryRegistrar(openTelemetry);
    }

    @Bean
    @ConditionalOnMissingBean
    Tracer platformTracer(Environment environment, OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(OtelProperties.serviceName(environment));
    }

    @Bean
    @ConditionalOnMissingBean
    EventConsumerTracer eventConsumerTracer(Tracer tracer) {
        return new OtelEventConsumerTracer(tracer);
    }

    @Bean
    @ConditionalOnMissingBean
    FilterRegistrationBean<OtelHttpServerFilter> otelHttpServerFilter(OpenTelemetry openTelemetry, Tracer tracer) {
        OtelHttpServerFilter filter = new OtelHttpServerFilter(openTelemetry, tracer);
        FilterRegistrationBean<OtelHttpServerFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(filter.getOrder());
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    HttpTracePropagationInterceptor httpTracePropagationInterceptor(OpenTelemetry openTelemetry) {
        return new HttpTracePropagationInterceptor(openTelemetry);
    }

    @Bean
    @ConditionalOnMissingBean
    static RestClientTracePropagationCustomizer restClientTracePropagationCustomizer(ObjectProvider<HttpTracePropagationInterceptor> interceptor) {
        return new RestClientTracePropagationCustomizer(interceptor);
    }
}

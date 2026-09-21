package com.trainticket.platformkit.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
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

/**
 * Wires the OpenTelemetry SDK for whichever signals the environment selects.
 *
 * The class-level condition covers either signal because the {@link OpenTelemetry}
 * instance and the {@link Resource} on it are shared: a metrics-only service still
 * needs both. The tracing beans carry their own condition so they stay absent when
 * only metrics are enabled, and the metrics beans live in
 * {@link PlatformOpenTelemetryMetricsConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(OtelSignalEnabledCondition.class)
public class PlatformOpenTelemetryConfiguration {
    /**
     * The one resource both providers use.
     *
     * A span and a metric point describing the same process must carry identical
     * resource attributes, or a query joining them by service.name returns nothing
     * for one of the two signals. Building it once is what makes that impossible.
     */
    @Bean
    @ConditionalOnMissingBean
    Resource platformOtelResource(Environment environment) {
        return Resource.getDefault().merge(Resource.create(Attributes.of(
            AttributeKey.stringKey("service.name"),
            OtelProperties.serviceName(environment)
        )));
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(OtelEnabledCondition.class)
    SpanExporterFactory spanExporterFactory() {
        return new OtlpSpanExporterFactory();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @Conditional(OtelEnabledCondition.class)
    SpanExporter spanExporter(Environment environment, SpanExporterFactory exporterFactory) {
        return exporterFactory.create(OtelProperties.otlpEndpoint(environment));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @Conditional(OtelEnabledCondition.class)
    SdkTracerProvider sdkTracerProvider(Resource resource, SpanExporter spanExporter) {
        return SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
            .build();
    }

    @Bean
    @ConditionalOnMissingBean
    OpenTelemetry openTelemetry(ObjectProvider<SdkTracerProvider> tracerProvider, ObjectProvider<SdkMeterProvider> meterProvider) {
        OpenTelemetrySdkBuilder builder = OpenTelemetrySdk.builder()
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()));
        SdkTracerProvider tracers = tracerProvider.getIfAvailable();
        if (tracers != null) {
            builder.setTracerProvider(tracers);
        }
        SdkMeterProvider meters = meterProvider.getIfAvailable();
        if (meters != null) {
            builder.setMeterProvider(meters);
        }
        return builder.build();
    }

    @Bean
    GlobalOpenTelemetryRegistrar globalOpenTelemetryRegistrar(ObjectProvider<OpenTelemetry> openTelemetry) {
        return new GlobalOpenTelemetryRegistrar(openTelemetry);
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(OtelEnabledCondition.class)
    Tracer platformTracer(Environment environment, OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(OtelProperties.serviceName(environment));
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(OtelEnabledCondition.class)
    EventConsumerTracer eventConsumerTracer(OpenTelemetry openTelemetry, Tracer tracer) {
        return new OtelEventConsumerTracer(openTelemetry, tracer);
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(OtelEnabledCondition.class)
    FilterRegistrationBean<OtelHttpServerFilter> otelHttpServerFilter(OpenTelemetry openTelemetry, Tracer tracer) {
        OtelHttpServerFilter filter = new OtelHttpServerFilter(openTelemetry, tracer);
        FilterRegistrationBean<OtelHttpServerFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(filter.getOrder());
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(OtelEnabledCondition.class)
    HttpTracePropagationInterceptor httpTracePropagationInterceptor(OpenTelemetry openTelemetry) {
        return new HttpTracePropagationInterceptor(openTelemetry);
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(OtelEnabledCondition.class)
    static RestClientTracePropagationCustomizer restClientTracePropagationCustomizer(ObjectProvider<HttpTracePropagationInterceptor> interceptor) {
        return new RestClientTracePropagationCustomizer(interceptor);
    }
}

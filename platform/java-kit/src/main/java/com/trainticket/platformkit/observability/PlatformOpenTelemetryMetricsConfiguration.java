package com.trainticket.platformkit.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Publishes this process's own runtime metrics over the OTLP connection the spans
 * already use.
 *
 * Two instrumentation sources meet in one {@link SdkMeterProvider}:
 *
 * <ul>
 *   <li>Micrometer, through {@link OpenTelemetryMeterRegistry}, for the JVM
 *       binders and HikariCP's own tracker. These are the meters the Java
 *       ecosystem defines, and reimplementing them would produce names nobody
 *       queries for.
 *   <li>The OpenTelemetry API directly, in {@link OtelHttpServerMetricsFilter},
 *       for HTTP server metrics, where the semantic conventions fix both the
 *       name and the unit.
 * </ul>
 *
 * The export interval is {@code OTEL_METRIC_EXPORT_INTERVAL} when set, which is
 * the standard SDK variable for it, and 60 seconds otherwise.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(OtelMetricsEnabledCondition.class)
public class PlatformOpenTelemetryMetricsConfiguration {
    @Bean
    @ConditionalOnMissingBean
    MetricExporterFactory metricExporterFactory() {
        return new OtlpMetricExporterFactory();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    MetricExporter metricExporter(Environment environment, MetricExporterFactory exporterFactory) {
        return exporterFactory.create(OtelProperties.otlpEndpoint(environment));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SdkMeterProvider sdkMeterProvider(Resource resource, MetricExporter metricExporter, Environment environment) {
        return SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                .setInterval(OtelProperties.metricExportInterval(environment))
                .build())
            .build();
    }

    /**
     * The Micrometer facade over the OpenTelemetry meter provider.
     *
     * Not in Prometheus mode: that mode exists for a Prometheus exporter and
     * appends unit suffixes to meter names, which would rename every meter
     * HikariCP and the JVM binders register. The identity convention keeps the
     * names those libraries document.
     */
    @Bean
    @ConditionalOnMissingBean
    MeterRegistry platformMeterRegistry(OpenTelemetry openTelemetry) {
        return OpenTelemetryMeterRegistry.builder(openTelemetry).build();
    }

    @Bean
    @ConditionalOnMissingBean
    JvmMemoryMetrics jvmMemoryMetrics(MeterRegistry meterRegistry) {
        return bind(new JvmMemoryMetrics(), meterRegistry);
    }

    /**
     * GC pause and concurrent-phase timings.
     *
     * Holds notification listeners on the GC MXBeans, so it is closed with the
     * context rather than left registered after a shutdown.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    JvmGcMetrics jvmGcMetrics(MeterRegistry meterRegistry) {
        return bind(new JvmGcMetrics(), meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    JvmThreadMetrics jvmThreadMetrics(MeterRegistry meterRegistry) {
        return bind(new JvmThreadMetrics(), meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    static HikariMetricsBinder hikariMetricsBinder(ObjectProvider<MeterRegistry> meterRegistry) {
        return new HikariMetricsBinder(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    FilterRegistrationBean<OtelHttpServerMetricsFilter> otelHttpServerMetricsFilter(OpenTelemetry openTelemetry, Environment environment) {
        OtelHttpServerMetricsFilter filter = new OtelHttpServerMetricsFilter(
            openTelemetry.getMeter(OtelProperties.serviceName(environment)));
        FilterRegistrationBean<OtelHttpServerMetricsFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(filter.getOrder());
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    OtelHttpClientMetricsInterceptor otelHttpClientMetricsInterceptor(OpenTelemetry openTelemetry, Environment environment) {
        return new OtelHttpClientMetricsInterceptor(
            openTelemetry.getMeter(OtelProperties.serviceName(environment)));
    }

    @Bean
    @ConditionalOnMissingBean
    static RestClientMetricsCustomizer restClientMetricsCustomizer(ObjectProvider<OtelHttpClientMetricsInterceptor> interceptor) {
        return new RestClientMetricsCustomizer(interceptor);
    }

    private static <T extends io.micrometer.core.instrument.binder.MeterBinder> T bind(T binder, MeterRegistry meterRegistry) {
        binder.bindTo(meterRegistry);
        return binder;
    }
}

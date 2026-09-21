package com.trainticket.platformkit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.HandlerMapping;

class PlatformOpenTelemetryMetricsConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            PlatformOpenTelemetryConfiguration.class,
            PlatformOpenTelemetryMetricsConfiguration.class));

    @Test
    void metricBeansAreAbsentWhenMetricsExporterIsNone() {
        contextRunner
            .withPropertyValues(
                "otel.traces.exporter=otlp",
                "otel.metrics.exporter=none",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=java-kit-test"
            )
            .withBean(SpanExporterFactory.class, () -> endpoint -> io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create())
            .run(context -> {
                assertThat(context).doesNotHaveBean(SdkMeterProvider.class);
                assertThat(context).doesNotHaveBean(MeterRegistry.class);
            });
    }

    /**
     * Metrics alone must be sufficient. The shared OpenTelemetry bean used to be
     * gated on tracing, which left this combination with no provider to publish
     * through.
     */
    @Test
    void metricsExportWorksWithoutTracing() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();

        contextRunner
            .withPropertyValues(
                "otel.traces.exporter=none",
                "otel.metrics.exporter=otlp",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=java-kit-test"
            )
            .withBean(MetricExporterFactory.class, () -> endpoint -> exporter)
            .run(context -> {
                assertThat(context).hasSingleBean(SdkMeterProvider.class);
                assertThat(context).doesNotHaveBean(SdkTracerProvider.class);
                assertThat(context.getBean(OpenTelemetry.class)).isNotNull();
            });
    }

    @Test
    void jvmMetricsReachTheExporterUnderTheirMicrometerNames() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();

        contextRunner
            .withPropertyValues(
                "otel.metrics.exporter=otlp",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=java-kit-test"
            )
            .withBean(MetricExporterFactory.class, () -> endpoint -> exporter)
            .run(context -> {
                context.getBean(SdkMeterProvider.class).forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);

                assertThat(metricNames(exporter))
                    .contains("jvm.memory.used", "jvm.memory.committed", "jvm.memory.max", "jvm.threads.live");
                assertThat(exporter.getFinishedMetricItems())
                    .allSatisfy(metric -> assertThat(metric.getResource().getAttribute(
                        io.opentelemetry.api.common.AttributeKey.stringKey("service.name")))
                        .isEqualTo("java-kit-test"));
            });
    }

    @Test
    void httpServerFilterRecordsDurationByRouteAndStatus() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();

        contextRunner
            .withPropertyValues(
                "otel.metrics.exporter=otlp",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=java-kit-test"
            )
            .withBean(MetricExporterFactory.class, () -> endpoint -> exporter)
            .run(context -> {
                @SuppressWarnings("unchecked")
                FilterRegistrationBean<OtelHttpServerMetricsFilter> registration =
                    context.getBean(FilterRegistrationBean.class, OtelHttpServerMetricsFilter.class);
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/order-1");
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/orders/{orderId}");
                MockHttpServletResponse response = new MockHttpServletResponse();

                registration.getFilter().doFilter(request, response, new MockFilterChain());

                context.getBean(SdkMeterProvider.class).forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(exporter.getFinishedMetricItems())
                    .filteredOn(metric -> metric.getName().equals("http.server.request.duration"))
                    .singleElement()
                    .satisfies(metric -> {
                        assertThat(metric.getUnit()).isEqualTo("s");
                        assertThat(metric.getHistogramData().getPoints())
                            .singleElement()
                            .satisfies(point -> {
                                assertThat(point.getCount()).isEqualTo(1);
                                // The route pattern, not the URI: /api/v1/orders/order-1
                                // would be one series per order id.
                                assertThat(point.getAttributes().get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey("http.route")))
                                    .isEqualTo("/api/v1/orders/{orderId}");
                                assertThat(point.getAttributes().get(
                                    io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")))
                                    .isEqualTo(200L);
                            });
                    });
            });
    }

    /**
     * The missing case: a pool bound is visible only if the pool's own gauges are
     * published. The names are HikariCP's, which is what a reader querying for a
     * saturated pool looks for.
     */
    @Test
    void hikariPoolGaugesArePublishedForADataSourceBean() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();

        contextRunner
            .withPropertyValues(
                "otel.metrics.exporter=otlp",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=java-kit-test"
            )
            .withBean(MetricExporterFactory.class, () -> endpoint -> exporter)
            .withBean(DataSource.class, PlatformOpenTelemetryMetricsConfigurationTest::unreachablePool)
            .run(context -> {
                context.getBean(SdkMeterProvider.class).forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);

                assertThat(metricNames(exporter)).contains(
                    "hikaricp.connections",
                    "hikaricp.connections.active",
                    "hikaricp.connections.idle",
                    "hikaricp.connections.pending",
                    "hikaricp.connections.max",
                    "hikaricp.connections.min",
                    // The acquire timer's companion max gauge. The timer's own
                    // histogram and hikaricp.connections.timeout are cumulative and
                    // appear once they have recorded, which needs a real acquisition;
                    // the max gauge is registered with the tracker and so proves the
                    // timer exists without one.
                    "hikaricp.connections.acquire.max",
                    "hikaricp.connections.usage.max",
                    "hikaricp.connections.creation.max");
            });
    }

    /**
     * Outbound calls, which is how a saturated callee is seen from the caller's
     * side. The interceptor is asserted through a RestClient built from the
     * post-processed builder, so what is proved is that the customizer installs
     * it -- an interceptor exercised directly would pass with the wiring absent.
     */
    @Test
    void restClientCallsAreRecordedByServerAndStatus() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();

        contextRunner
            .withPropertyValues(
                "otel.metrics.exporter=otlp",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=java-kit-test"
            )
            .withBean(MetricExporterFactory.class, () -> endpoint -> exporter)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .run(context -> {
                RestClient client = context.getBean(RestClient.Builder.class)
                    // A request factory that answers without a network, so the
                    // recorded sample comes from the interceptor rather than from
                    // a connection failure.
                    .requestFactory(new StubRequestFactory())
                    .build();
                client.get().uri("http://payment:8080/api/v1/payments/payment-1").retrieve().toBodilessEntity();

                context.getBean(SdkMeterProvider.class).forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(exporter.getFinishedMetricItems())
                    .filteredOn(metric -> metric.getName().equals("http.client.request.duration"))
                    .singleElement()
                    .satisfies(metric -> {
                        assertThat(metric.getUnit()).isEqualTo("s");
                        assertThat(metric.getHistogramData().getPoints())
                            .singleElement()
                            .satisfies(point -> {
                                assertThat(point.getAttributes().get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey("server.address")))
                                    .isEqualTo("payment");
                                assertThat(point.getAttributes().get(
                                    io.opentelemetry.api.common.AttributeKey.longKey("server.port")))
                                    .isEqualTo(8080L);
                                assertThat(point.getAttributes().get(
                                    io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")))
                                    .isEqualTo(200L);
                                // The path holds an id, so url.full must not be an
                                // attribute; which route was called is answerable
                                // from the server side of the same call.
                                assertThat(point.getAttributes().get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey("url.full")))
                                    .isNull();
                            });
                    });
            });
    }

    /**
     * Answers 200 with an empty body without opening a socket.
     */
    private static final class StubRequestFactory implements org.springframework.http.client.ClientHttpRequestFactory {
        @Override
        public org.springframework.http.client.ClientHttpRequest createRequest(java.net.URI uri, org.springframework.http.HttpMethod method) {
            org.springframework.mock.http.client.MockClientHttpRequest request =
                new org.springframework.mock.http.client.MockClientHttpRequest(method, uri);
            request.setResponse(new org.springframework.mock.http.client.MockClientHttpResponse(
                new byte[0], org.springframework.http.HttpStatus.OK));
            return request;
        }
    }

    private static List<String> metricNames(InMemoryMetricExporter exporter) {
        return exporter.getFinishedMetricItems().stream().map(MetricData::getName).toList();
    }

    /**
     * A Hikari pool that reports its own state without a database behind it.
     *
     * {@link DataSources#fromDatabaseUrl} sets no initializationFailTimeout, so it
     * keeps HikariCP's default of 1: the constructor opens one connection and
     * throws when that fails. A negative value makes construction return
     * immediately with an empty pool, which is all this test needs -- the gauges
     * are registered when the tracker is attached, not when a connection exists.
     */
    private static DataSource unreachablePool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/unused");
        config.setUsername("unused");
        config.setPassword("unused");
        config.setPoolName("java-kit-test");
        config.setInitializationFailTimeout(-1);
        config.setConnectionTimeout(250);
        return new HikariDataSource(config);
    }
}

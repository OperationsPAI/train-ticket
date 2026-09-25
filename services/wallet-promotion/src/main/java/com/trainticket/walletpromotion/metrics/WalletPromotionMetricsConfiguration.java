package com.trainticket.walletpromotion.metrics;

import com.trainticket.walletpromotion.Application;
import com.trainticket.walletpromotion.application.PromotionRepository;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the balance gauge to the durable repository and the operation counter to
 * the process meter.
 *
 * <p>The meter comes from the shared OpenTelemetry bean rather than
 * {@code GlobalOpenTelemetry.get()}: calling the global too early permanently
 * installs the no-op provider, and this configuration may be created before the
 * SDK registrar runs. When no signal is enabled the bean is the no-op recorder.
 */
@Configuration(proxyBeanMethods = false)
public class WalletPromotionMetricsConfiguration {
    @Bean
    @ConditionalOnMissingBean
    WalletBalanceSnapshotProvider walletBalanceSnapshotProvider(PromotionRepository repository) {
        return repository::walletBalanceSnapshot;
    }

    @Bean
    @ConditionalOnMissingBean
    WalletPromotionMetrics walletPromotionMetrics(
        ObjectProvider<OpenTelemetry> openTelemetry,
        WalletBalanceSnapshotProvider snapshotProvider
    ) {
        OpenTelemetry otel = openTelemetry.getIfAvailable();
        if (otel == null) {
            return WalletPromotionMetrics.noop();
        }
        return new OpenTelemetryWalletPromotionMetrics(otel.getMeter(serviceName()), snapshotProvider);
    }

    private static String serviceName() {
        return Optional.ofNullable(System.getenv("OTEL_SERVICE_NAME"))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .orElse(Application.profile().serviceId());
    }
}

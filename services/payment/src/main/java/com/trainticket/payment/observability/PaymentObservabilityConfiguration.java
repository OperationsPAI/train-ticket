package com.trainticket.payment.observability;

import com.trainticket.payment.infrastructure.persistence.PostgresPaymentConservationLedger;
import io.opentelemetry.api.OpenTelemetry;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the payment business metrics to the process's existing OpenTelemetry
 * meter and to whichever durable store is configured.
 */
@Configuration(proxyBeanMethods = false)
public class PaymentObservabilityConfiguration {
    @Bean
    @ConditionalOnMissingBean
    PaymentMetricRecorder paymentMetricRecorder(ObjectProvider<OpenTelemetry> openTelemetry) {
        OpenTelemetry sdk = openTelemetry.getIfAvailable();
        return sdk == null
            ? NoopPaymentMetrics.INSTANCE
            : new OpenTelemetryPaymentMetrics(sdk.getMeter("payment"));
    }

    @Bean
    @ConditionalOnMissingBean
    PaymentConservationLedger paymentConservationLedger(ObjectProvider<DataSource> dataSources) {
        DataSource dataSource = dataSources.getIfAvailable();
        return dataSource == null
            ? PaymentConservationLedger.empty()
            : new PostgresPaymentConservationLedger(dataSource);
    }
}

package com.trainticket.walletpromotion.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.trainticket.walletpromotion.application.PromotionRepository;
import com.trainticket.walletpromotion.application.WalletPromotionService;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class WalletPromotionMetricsConfigurationTest {
    @Test
    void usesTheNoopRecorderWhenNoOpenTelemetrySignalIsConfigured() {
        try (AnnotationConfigApplicationContext context = context(false)) {
            assertThat(context.getBean(WalletPromotionMetrics.class)).isInstanceOf(NoOpWalletPromotionMetrics.class);
            assertThat(context.getBean(WalletBalanceSnapshotProvider.class)).isNotNull();
        }
    }

    @Test
    void registersTheOpenTelemetryRecorderWhenTheSharedSignalBeanExists() {
        try (AnnotationConfigApplicationContext context = context(true)) {
            assertThat(context.getBean(WalletPromotionMetrics.class)).isInstanceOf(OpenTelemetryWalletPromotionMetrics.class);
        }
    }

    @Test
    void serviceHasExactlyOneAutowiredConstructorAndItTakesTheRecorder() {
        var autowired = Arrays.stream(WalletPromotionService.class.getDeclaredConstructors())
            .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
            .toList();

        assertThat(autowired).hasSize(1);
        assertThat(autowired.getFirst().getParameterTypes()).contains(WalletPromotionMetrics.class);
    }

    private static AnnotationConfigApplicationContext context(boolean withOpenTelemetry) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(PromotionRepository.class, () -> mock(PromotionRepository.class));
        if (withOpenTelemetry) {
            context.registerBean(OpenTelemetry.class, OpenTelemetry::noop);
        }
        context.register(WalletPromotionMetricsConfiguration.class);
        context.refresh();
        return context;
    }
}

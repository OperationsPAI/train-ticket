package com.trainticket.platformkit.observability;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Attaches HikariCP's own Micrometer tracker to every pool in the context.
 *
 * A BeanPostProcessor rather than a change to
 * {@link com.trainticket.platformkit.persistence.DataSources}: that factory is
 * static and has no registry to hand, and each of the twelve Java services
 * declares its own DataSource bean from it. Post-processing reaches all of them
 * from one place.
 *
 * {@code setMetricsTrackerFactory} is called after construction, which HikariCP
 * supports: the sealing that a constructed HikariDataSource applies does not
 * cover this setter, and the live pool picks the tracker up. The pool therefore
 * begins publishing without being rebuilt.
 */
public final class HikariMetricsBinder implements BeanPostProcessor {
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public HikariMetricsBinder(ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof HikariDataSource dataSource)) {
            return bean;
        }
        MeterRegistry registry = meterRegistry.getIfAvailable();
        // A tracker can be installed once per pool. A second attempt throws, so a
        // pool that already carries one -- its own configuration, or a second pass
        // over the same bean -- is left as it is.
        if (registry == null || dataSource.getMetricsTrackerFactory() != null) {
            return bean;
        }
        dataSource.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));
        return bean;
    }
}

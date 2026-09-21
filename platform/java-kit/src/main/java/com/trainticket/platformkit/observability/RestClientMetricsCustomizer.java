package com.trainticket.platformkit.observability;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.client.RestClient;

/**
 * Attaches the outbound metrics interceptor to every {@link RestClient.Builder}
 * in the context.
 *
 * A BeanPostProcessor for the same reason
 * {@link RestClientTracePropagationCustomizer} is one: each service builds its
 * own clients from a builder bean, so post-processing reaches all of them from
 * one place.
 */
public final class RestClientMetricsCustomizer implements BeanPostProcessor {
    private final ObjectProvider<OtelHttpClientMetricsInterceptor> interceptor;

    public RestClientMetricsCustomizer(ObjectProvider<OtelHttpClientMetricsInterceptor> interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof RestClient.Builder builder) {
            OtelHttpClientMetricsInterceptor metricsInterceptor = interceptor.getIfAvailable();
            if (metricsInterceptor != null) {
                builder.requestInterceptor(metricsInterceptor);
            }
        }
        return bean;
    }
}

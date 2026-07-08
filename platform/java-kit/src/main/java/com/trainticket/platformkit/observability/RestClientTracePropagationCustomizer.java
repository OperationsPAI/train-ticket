package com.trainticket.platformkit.observability;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.client.RestClient;

public final class RestClientTracePropagationCustomizer implements BeanPostProcessor {
    private final ObjectProvider<HttpTracePropagationInterceptor> interceptor;

    public RestClientTracePropagationCustomizer(ObjectProvider<HttpTracePropagationInterceptor> interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof RestClient.Builder builder) {
            HttpTracePropagationInterceptor tracePropagationInterceptor = interceptor.getIfAvailable();
            if (tracePropagationInterceptor != null) {
                builder.requestInterceptor(tracePropagationInterceptor);
            }
        }
        return bean;
    }
}

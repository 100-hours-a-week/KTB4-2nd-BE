package com.yeodam.yeodambe.common.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.request-rate-limit", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RequestRateLimitProperties.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestRateLimitWebConfig implements WebMvcConfigurer {

    private final RequestRateLimitInterceptor interceptor;

    public RequestRateLimitWebConfig(RequestRateLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(
                "/trips", "/attachments/bulk-download", "/stories/*/tone-preview");
    }
}

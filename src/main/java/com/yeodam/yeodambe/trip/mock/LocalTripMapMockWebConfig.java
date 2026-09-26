package com.yeodam.yeodambe.trip.mock;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Profile("local")
@Configuration
@RequiredArgsConstructor
public class LocalTripMapMockWebConfig implements WebMvcConfigurer {

    private final LocalTripMapMockInterceptor localTripMapMockInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localTripMapMockInterceptor)
                .addPathPatterns("/trips", "/trips/map");
    }
}

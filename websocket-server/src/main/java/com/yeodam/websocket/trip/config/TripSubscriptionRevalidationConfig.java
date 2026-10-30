package com.yeodam.websocket.trip.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class TripSubscriptionRevalidationConfig {
    private static final int MAX_CONCURRENT_CHECKS = 10;

    @Bean(name = "tripSubscriptionRevalidationTaskScheduler", defaultCandidate = false)
    ThreadPoolTaskScheduler tripSubscriptionRevalidationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("trip-permission-schedule-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean(name = "tripSubscriptionRevalidationExecutor", defaultCandidate = false)
    ThreadPoolTaskExecutor tripSubscriptionRevalidationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(MAX_CONCURRENT_CHECKS);
        executor.setMaxPoolSize(MAX_CONCURRENT_CHECKS);
        executor.setQueueCapacity(MAX_CONCURRENT_CHECKS);
        executor.setThreadNamePrefix("trip-permission-check-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}

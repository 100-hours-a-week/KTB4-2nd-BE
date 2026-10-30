package com.yeodam.yeodambe.trip.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class TripEditNotificationConfig {

    @Bean(name = "tripEditNotificationExecutor", defaultCandidate = false)
    ThreadPoolTaskExecutor tripEditNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("trip-edit-notification-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}

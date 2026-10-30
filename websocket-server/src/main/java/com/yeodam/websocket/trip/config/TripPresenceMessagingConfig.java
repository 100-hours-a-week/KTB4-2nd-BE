package com.yeodam.websocket.trip.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TripPresenceProperties.class)
public class TripPresenceMessagingConfig {
    @Bean
    ThreadPoolTaskScheduler tripPresenceTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("trip-presence-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    ThreadPoolTaskScheduler tripPresenceAccessScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("trip-presence-access-scheduler-");
        return scheduler;
    }

    @Bean
    ThreadPoolTaskExecutor tripPresenceAccessExecutor() {
        return executor("trip-presence-access-", 16, 0);
    }

    @Bean
    ThreadPoolTaskExecutor tripPresenceCleanupExecutor() {
        return executor("trip-presence-cleanup-", 4, 1000);
    }

    private ThreadPoolTaskExecutor executor(String prefix, int workers, int capacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(capacity);
        return executor;
    }

}

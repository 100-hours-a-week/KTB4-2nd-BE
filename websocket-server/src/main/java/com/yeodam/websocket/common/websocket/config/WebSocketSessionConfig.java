package com.yeodam.websocket.common.websocket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class WebSocketSessionConfig {

    @Bean
    ThreadPoolTaskScheduler webSocketSessionTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("websocket-session-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
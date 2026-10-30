package com.yeodam.websocket.trip.config;

import com.yeodam.websocket.common.websocket.config.WebSocketSessionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TripSubscriptionRevalidationConfigTest {
    @Test
    void 로그인_스케줄이_바빠도_권한_스케줄이_실행된다() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                WebSocketSessionConfig.class, TripSubscriptionRevalidationConfig.class)) {
            TaskScheduler login = context.getBean("webSocketSessionRevalidationTaskScheduler", TaskScheduler.class);
            TaskScheduler permission = context.getBean("tripSubscriptionRevalidationTaskScheduler", TaskScheduler.class);
            ThreadPoolTaskExecutor executor = context.getBean("tripSubscriptionRevalidationExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(10);
            assertThat(executor.getMaxPoolSize()).isEqualTo(10);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(10);
            CountDownLatch loginStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch permissionRan = new CountDownLatch(1);
            login.schedule(() -> {
                loginStarted.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }, Instant.now());
            try {
                assertThat(loginStarted.await(2, TimeUnit.SECONDS)).isTrue();
                permission.schedule(permissionRan::countDown, Instant.now());
                assertThat(permissionRan.await(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
        }
    }
}

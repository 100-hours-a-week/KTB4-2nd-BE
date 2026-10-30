package com.yeodam.yeodambe.trip.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripEditNotificationConfigTest {

    @Test
    void 즉시_발행_작업을_제한하고_앱_종료_시_작업_스레드를_닫는다() throws InterruptedException {
        CountDownLatch workersStarted = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        ThreadPoolTaskExecutor executor;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TripEditNotificationConfig.class)) {
            executor = context.getBean("tripEditNotificationExecutor", ThreadPoolTaskExecutor.class);
            try {
                for (int index = 0; index < 2; index++) {
                    executor.execute(() -> {
                        workersStarted.countDown();
                        try {
                            releaseWorkers.await();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
                assertThat(workersStarted.await(5, TimeUnit.SECONDS)).isTrue();
                IntStream.range(0, 100).forEach(index -> executor.execute(() -> {
                }));
                assertThatThrownBy(() -> executor.execute(() -> {
                }))
                        .isInstanceOf(RejectedExecutionException.class);
                assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(100);
            } finally {
                releaseWorkers.countDown();
            }
        }
        assertThat(executor.getThreadPoolExecutor().isShutdown()).isTrue();
        assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
}

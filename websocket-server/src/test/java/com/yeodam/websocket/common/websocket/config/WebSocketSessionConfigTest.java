package com.yeodam.websocket.common.websocket.config;

import com.yeodam.websocket.common.websocket.client.BackendAuthenticationClient;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionRevalidationService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebSocketSessionConfigTest {

    @Test
    void registersRevalidationWithItsOwnSchedulerAndThirtySecondDelay() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(WebSocketSessionConfig.class);
            context.registerBean(WebSocketSessionRevalidationService.class, () ->
                    new WebSocketSessionRevalidationService(
                            mock(WebSocketSessionManager.class), mock(BackendAuthenticationClient.class)
                    ));
            context.refresh();

            var processor = context.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(processor.getScheduledTasks()).hasSize(1);
            var scheduler = context.getBean("webSocketSessionRevalidationTaskScheduler", ThreadPoolTaskScheduler.class);
            var expiryScheduler = context.getBean("webSocketSessionTaskScheduler", ThreadPoolTaskScheduler.class);
            assertThat(scheduler).isNotSameAs(expiryScheduler);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).hasSize(1);
            assertThat(expiryScheduler.getScheduledThreadPoolExecutor().getQueue()).isEmpty();

            Scheduled schedule = WebSocketSessionRevalidationService.class
                    .getMethod("revalidateSessions").getAnnotation(Scheduled.class);
            assertThat(schedule.scheduler()).isEqualTo("webSocketSessionRevalidationTaskScheduler");
            assertThat(schedule.initialDelay()).isEqualTo(30_000);
            assertThat(schedule.fixedDelay()).isEqualTo(30_000);
        }
    }
}

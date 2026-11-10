package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TripProcessingStatusSchedulerTest {
    private final TripCreationExecutionRepository creations = mock(TripCreationExecutionRepository.class);
    private final AdditionalAttachmentExecutionRepository additions = mock(AdditionalAttachmentExecutionRepository.class);
    private final TripProcessingStatusService statuses = mock(TripProcessingStatusService.class);
    private final TripAnalysisExecutionRepository analyses = mock(TripAnalysisExecutionRepository.class);
    private final TripProcessingStatusScheduler scheduler =
            new TripProcessingStatusScheduler(
                    creations, additions, statuses, analyses
            );

    @Test
    void 생성과_추가와_분석을_정확한_실행_ID로_수집한다() {
        when(creations.findAwaitingAiStatus()).thenReturn(List.of(creation(7L, 42L)));
        when(additions.findAwaitingAiStatus()).thenReturn(List.of(addition(8L, 43L)));
        when(analyses.findAwaitingAiStatus()).thenReturn(List.of(new TripAnalysisExecution("analysis-9", 9L, 44L)));

        scheduler.synchronizePendingExecutions();

        InOrder order = inOrder(statuses);
        order.verify(statuses).synchronizeCreation("creation-7");
        order.verify(statuses).synchronizeAddition("addition-8");
        order.verify(statuses).synchronizeAnalysis("analysis-9");
        verifyNoMoreInteractions(statuses);
    }

    @Test
    void 한_실행의_실패가_뒤의_실행과_추가_목록_처리를_막지_않는다() {
        when(creations.findAwaitingAiStatus()).thenReturn(List.of(creation(7L, 42L), creation(8L, 43L)));
        when(additions.findAwaitingAiStatus()).thenReturn(List.of(addition(9L, 44L)));
        when(analyses.findAwaitingAiStatus()).thenReturn(List.of(
                new TripAnalysisExecution("analysis-10", 10L, 45L),
                new TripAnalysisExecution("analysis-11", 11L, 46L)
        ));
        doThrow(IllegalStateException.class).when(statuses).synchronizeCreation("creation-7");
        doThrow(IllegalStateException.class).when(statuses).synchronizeAnalysis("analysis-10");

        scheduler.synchronizePendingExecutions();

        verify(statuses).synchronizeCreation("creation-7");
        verify(statuses).synchronizeCreation("creation-8");
        verify(statuses).synchronizeAddition("addition-9");
        verify(statuses).synchronizeAnalysis("analysis-10");
        verify(statuses).synchronizeAnalysis("analysis-11");
    }

    @Test
    void 상태_수집_대상이_없으면_AI를_조회하지_않는다() {
        scheduler.synchronizePendingExecutions();

        verify(creations).findAwaitingAiStatus();
        verify(additions).findAwaitingAiStatus();
        verify(analyses).findAwaitingAiStatus();
        verifyNoInteractions(statuses);
    }

    @Test
    void 설정이_true일_때만_스케줄러를_활성화한다() {
        try (AnnotationConfigApplicationContext context = context(Map.of("app.trip.status-polling-enabled", "true"))) {
            context.register(TripProcessingStatusScheduler.class);
            context.refresh();
            assertThat(context.getBeansOfType(TripProcessingStatusScheduler.class)).hasSize(1);
        }
    }

    @Test
    void 설정이_false이면_스케줄러를_비활성화한다() {
        try (AnnotationConfigApplicationContext context = context(Map.of("app.trip.status-polling-enabled", "false"))) {
            context.register(TripProcessingStatusScheduler.class);
            context.refresh();
            assertThat(context.getBeansOfType(TripProcessingStatusScheduler.class)).isEmpty();
            verifyNoInteractions(creations, additions, statuses);
        }
    }

    @Test
    void 설정이_없으면_스케줄러를_활성화하지_않는다() {
        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            context.register(TripProcessingStatusScheduler.class);
            context.refresh();
            assertThat(context.getBeansOfType(TripProcessingStatusScheduler.class)).isEmpty();
        }
    }

    @Test
    void 프론트엔드_요청_없이_Spring이_상태를_수집한다() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        when(creations.findAwaitingAiStatus()).thenReturn(List.of(creation(7L, 42L)));
        doAnswer(invocation -> {
            called.countDown();
            return null;
        }).when(statuses).synchronizeCreation("creation-7");
        try (AnnotationConfigApplicationContext context = context(Map.of("app.trip.status-polling-enabled", "true"))) {
            context.register(SchedulingConfiguration.class);
            context.refresh();

            assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(TripCreationExecutionRepository.class, () -> creations);
        context.registerBean(AdditionalAttachmentExecutionRepository.class, () -> additions);
        context.registerBean(TripProcessingStatusService.class, () -> statuses);
        context.registerBean(TripAnalysisExecutionRepository.class, () -> analyses);
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(TripProcessingStatusScheduler.class)
    static class SchedulingConfiguration {
    }

    private TripCreationExecution creation(Long tripId, Long userId) {
        return new TripCreationExecution("creation-" + tripId, tripId, userId, 2);
    }

    private AdditionalAttachmentExecution addition(Long tripId, Long userId) {
        return new AdditionalAttachmentExecution("addition-" + tripId, tripId, userId, 2, LocalDateTime.now());
    }
}

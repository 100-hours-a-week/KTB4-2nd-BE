package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

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
    private final TripProcessingStatusScheduler scheduler =
            new TripProcessingStatusScheduler(creations, additions, statuses);

    @Test
    void checksBothCreationAndAdditionalExecutionsWithTheirOwners() {
        when(creations.findAwaitingAiStatus()).thenReturn(List.of(creation(7L, 42L)));
        when(additions.findAwaitingAiStatus()).thenReturn(List.of(addition(8L, 43L)));

        scheduler.synchronizePendingExecutions();

        var order = inOrder(statuses);
        order.verify(statuses).findStatus(7L, 42L);
        order.verify(statuses).findStatus(8L, 43L);
        verifyNoMoreInteractions(statuses);
    }

    @Test
    void aFailedExecutionDoesNotStopFollowingExecutionsOrTheAdditionalList() {
        when(creations.findAwaitingAiStatus()).thenReturn(List.of(creation(7L, 42L), creation(8L, 43L)));
        when(additions.findAwaitingAiStatus()).thenReturn(List.of(addition(9L, 44L)));
        when(statuses.findStatus(7L, 42L)).thenThrow(new IllegalStateException("AI unavailable"));

        scheduler.synchronizePendingExecutions();

        verify(statuses).findStatus(7L, 42L);
        verify(statuses).findStatus(8L, 43L);
        verify(statuses).findStatus(9L, 44L);
    }

    @Test
    void doesNotQueryAiWhenNoExecutionsNeedStatusCollection() {
        scheduler.synchronizePendingExecutions();

        verify(creations).findAwaitingAiStatus();
        verify(additions).findAwaitingAiStatus();
        verifyNoInteractions(statuses);
    }

    @Test
    void enablesTheSchedulerOnlyWhenConfiguredTrue() {
        try (var context = context(Map.of("app.trip.status-polling-enabled", "true"))) {
            context.register(TripProcessingStatusScheduler.class);
            context.refresh();
            assertThat(context.getBeansOfType(TripProcessingStatusScheduler.class)).hasSize(1);
        }
    }

    @Test
    void disablesTheSchedulerWhenConfiguredFalse() {
        try (var context = context(Map.of("app.trip.status-polling-enabled", "false"))) {
            context.register(TripProcessingStatusScheduler.class);
            context.refresh();
            assertThat(context.getBeansOfType(TripProcessingStatusScheduler.class)).isEmpty();
            verifyNoInteractions(creations, additions, statuses);
        }
    }

    @Test
    void doesNotEnableTheSchedulerWhenThePropertyIsMissing() {
        try (var context = context(Map.of())) {
            context.register(TripProcessingStatusScheduler.class);
            context.refresh();
            assertThat(context.getBeansOfType(TripProcessingStatusScheduler.class)).isEmpty();
        }
    }

    @Test
    void springInvokesStatusCollectionWithoutAFrontendRequest() throws Exception {
        var called = new CountDownLatch(1);
        when(creations.findAwaitingAiStatus()).thenReturn(List.of(creation(7L, 42L)));
        when(statuses.findStatus(7L, 42L)).thenAnswer(invocation -> {
            called.countDown();
            return null;
        });
        try (var context = context(Map.of("app.trip.status-polling-enabled", "true"))) {
            context.register(SchedulingConfiguration.class);
            context.refresh();

            assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(TripCreationExecutionRepository.class, () -> creations);
        context.registerBean(AdditionalAttachmentExecutionRepository.class, () -> additions);
        context.registerBean(TripProcessingStatusService.class, () -> statuses);
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

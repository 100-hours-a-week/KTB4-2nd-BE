package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StoryGenerationStatusSchedulerTest {
    private final StoryGenerationJobRepository jobs = mock(StoryGenerationJobRepository.class);
    private final StoryGenerationOutboxRepository outboxes = mock(StoryGenerationOutboxRepository.class);
    private final StoryGenerationStatusSyncService sync = mock(StoryGenerationStatusSyncService.class);
    private final StoryGenerationStatusScheduler scheduler = new StoryGenerationStatusScheduler(
            jobs, outboxes, sync, new StoryGenerationTimeoutService(jobs, outboxes, mock(JdbcTemplate.class)));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void queriesOnlyActiveStatesAndPassesEachOriginalRequestId() {
        var preparedJobs = List.of(job(10L), job(20L));
        when(jobs.findAllByStatusIn(anyList())).thenReturn(preparedJobs);
        prepareOutbox(10L, "request-10");
        prepareOutbox(20L, "request-20");
        doAnswer(call -> {
            assertThat(MDC.get("request_id")).isEqualTo("request-" + call.getArgument(0));
            assertThat(MDC.get("job_id")).isEqualTo("execution-" + call.getArgument(0));
            assertThat(MDC.get("workflow")).isEqualTo("story_generation");
            return null;
        }).when(sync).synchronize(org.mockito.ArgumentMatchers.anyLong());

        scheduler.synchronizePendingJobs();

        verify(jobs).findAllByStatusIn(List.of(StoryGenerationJob.Status.QUEUED, StoryGenerationJob.Status.PROCESSING));
        var ordered = inOrder(sync);
        ordered.verify(sync).synchronize(10L);
        ordered.verify(sync).synchronize(20L);
        assertThat(MDC.get("request_id")).isNull();
    }

    @Test
    void failureDoesNotStopNextJobAndRestoresPreviousRequestId() {
        MDC.put("request_id", "previous-request");
        var preparedJobs = List.of(job(10L), job(20L));
        when(jobs.findAllByStatusIn(anyList())).thenReturn(preparedJobs);
        prepareOutbox(10L, "request-10");
        prepareOutbox(20L, "request-20");
        doThrow(new IllegalStateException("test failure")).when(sync).synchronize(10L);
        doAnswer(call -> {
            assertThat(MDC.get("request_id")).isEqualTo("request-20");
            return null;
        }).when(sync).synchronize(20L);

        scheduler.synchronizePendingJobs();

        verify(sync).synchronize(20L);
        assertThat(MDC.get("request_id")).isEqualTo("previous-request");
    }

    @Test
    void missingOutboxSkipsOnlyThatJob() {
        var preparedJobs = List.of(job(10L), job(20L));
        when(jobs.findAllByStatusIn(anyList())).thenReturn(preparedJobs);
        when(outboxes.findByGenerationId(10L)).thenReturn(Optional.empty());
        prepareOutbox(20L, "request-20");

        scheduler.synchronizePendingJobs();

        verify(sync).synchronize(20L);
        org.mockito.Mockito.verifyNoMoreInteractions(sync);
    }

    @Test
    void emptyListDoesNotQueryOutboxOrAi() {
        when(jobs.findAllByStatusIn(anyList())).thenReturn(List.of());

        scheduler.synchronizePendingJobs();

        verifyNoInteractions(outboxes, sync);
    }

    @Test
    void disabledPropertyDoesNotRegisterScheduler() {
        try (var context = context(false)) {
            context.refresh();

            assertThat(context.getBeansOfType(StoryGenerationStatusScheduler.class)).isEmpty();
            verifyNoInteractions(jobs, outboxes, sync);
        }
    }

    @Test
    void springActuallyInvokesScheduledMethodMoreThanOnce() throws Exception {
        var calls = new CountDownLatch(2);
        when(jobs.findAllByStatusIn(anyList())).thenAnswer(call -> {
            calls.countDown();
            return List.of();
        });

        try (var context = context(true)) {
            context.refresh();

            assertThat(calls.await(6, TimeUnit.SECONDS)).isTrue();
            assertThat(context.getBeansOfType(StoryGenerationStatusScheduler.class)).hasSize(1);
        }
    }

    @Test
    void overdueJobIsFailedWithoutAnotherAiStatusRequest() {
        var overdue = job(10L);
        ReflectionTestUtils.setField(overdue, "createdAt", LocalDateTime.now().minusHours(2));
        when(jobs.findAllByStatusIn(anyList())).thenReturn(List.of(overdue));
        when(jobs.findByIdForUpdate(10L)).thenReturn(Optional.of(overdue));
        var outbox = new StoryGenerationOutbox(10L, "{}", "request-10");
        outbox.publicationSucceeded(LocalDateTime.now().minusHours(2));
        when(outboxes.findByGenerationId(10L)).thenReturn(Optional.of(outbox));

        scheduler.synchronizePendingJobs();

        assertThat(overdue.getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(overdue.getErrorCode()).isEqualTo("STORY_GENERATION_TIMEOUT");
        verifyNoInteractions(sync);
    }

    private AnnotationConfigApplicationContext context(boolean enabled) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("app.story.status-polling-enabled", Boolean.toString(enabled))));
        context.registerBean(StoryGenerationJobRepository.class, () -> jobs);
        context.registerBean(StoryGenerationOutboxRepository.class, () -> outboxes);
        context.registerBean(StoryGenerationStatusSyncService.class, () -> sync);
        context.registerBean(StoryGenerationTimeoutService.class,
                () -> new StoryGenerationTimeoutService(jobs, outboxes, mock(JdbcTemplate.class)));
        context.register(SchedulingConfiguration.class);
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(StoryGenerationStatusScheduler.class)
    static class SchedulingConfiguration {
    }

    private void prepareOutbox(Long id, String requestId) {
        when(outboxes.findByGenerationId(id)).thenReturn(Optional.of(new StoryGenerationOutbox(id, "{}", requestId)));
    }

    private StoryGenerationJob job(Long id) {
        var job = new StoryGenerationJob("execution-" + id, 241L, 2L, Story.Mood.PLAIN);
        ReflectionTestUtils.setField(job, "id", id);
        ReflectionTestUtils.setField(job, "createdAt", LocalDateTime.now());
        when(jobs.findByIdForUpdate(id)).thenReturn(Optional.of(job));
        return job;
    }
}

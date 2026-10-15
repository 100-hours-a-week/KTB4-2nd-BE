package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StoryGenerationTimeoutServiceTest {
    private final StoryGenerationJobRepository jobs = mock(StoryGenerationJobRepository.class);
    private final StoryGenerationOutboxRepository outboxes =
            mock(StoryGenerationOutboxRepository.class);
    private final StoryGenerationTimeoutService service = new StoryGenerationTimeoutService(jobs, outboxes);
    private final LocalDateTime created = LocalDateTime.of(2026, 10, 12, 12, 0);

    private StoryGenerationJob prepare() {
        var job = new StoryGenerationJob("execution-1", 7L, 2L, Story.Mood.PLAIN);
        ReflectionTestUtils.setField(job, "createdAt", created);
        when(jobs.findByIdForUpdate(1L)).thenReturn(Optional.of(job));
        var outbox = new StoryGenerationOutbox(1L, "{}", "request");
        outbox.publicationSucceeded(created.plusHours(2));
        when(outboxes.findByGenerationId(1L)).thenReturn(Optional.of(outbox));
        return job;
    }

    @Test
    void unpublishedJobDoesNotExpireAfterCreationDeadline() {
        var job = prepare();
        when(outboxes.findByGenerationId(1L)).thenReturn(Optional.of(
                new StoryGenerationOutbox(1L, "{}", "request")));
        assertThat(service.expire(1L, created.plusDays(2))).isFalse();
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
    }

    @Test
    void justBeforeOneHourKeepsQueuedJob() {
        var job = prepare();
        assertThat(service.expire(1L, created.plusHours(3).minusNanos(1))).isFalse();
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
    }

    @Test
    void exactlyOneHourFailsQueuedJob() {
        var job = prepare();
        assertThat(service.expire(1L, created.plusHours(3))).isTrue();
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(job.getErrorCode()).isEqualTo("STORY_GENERATION_TIMEOUT");
        verify(jobs).findByIdForUpdate(1L);
    }

    @Test
    void progressDoesNotExtendOriginalDeadline() {
        var job = prepare();
        job.updateProcessing(80);
        assertThat(service.expire(1L, created.plusHours(3))).isTrue();
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(job.getProgress()).isEqualTo(80);
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void doesNotOverwriteFinishedJob(StoryGenerationJob.Status status) {
        var job = prepare();
        switch (status) {
            case COMPLETED -> job.complete(10L);
            case FAILED -> job.fail("AI_FAILED");
            case CANCELED -> job.cancel();
            default -> throw new AssertionError();
        }
        String error = job.getErrorCode();
        assertThat(service.expire(1L, created.plusHours(2))).isTrue();
        assertThat(job.getStatus()).isEqualTo(status);
        assertThat(job.getErrorCode()).isEqualTo(error);
    }

    @Test
    void missingJobIsSkipped() {
        when(jobs.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThat(service.expire(1L, created.plusHours(2))).isTrue();
    }
}

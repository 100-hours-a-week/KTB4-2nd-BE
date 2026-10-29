package com.yeodam.yeodambe.story.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryGenerationStatusVersionTest {

    @Test
    void incrementsOnlyWhenThePublicStatusChanges() {
        StoryGenerationJob job = job();
        assertThat(job.getStatusVersion()).isZero();

        job.updateProcessing(10);
        assertThat(job.getStatusVersion()).isEqualTo(1);

        job.updateProcessing(40);
        job.updateProcessing(40);
        assertThat(job.getStatusVersion()).isEqualTo(1);

        job.complete(100L);
        assertThat(job.getStatusVersion()).isEqualTo(2);
        assertThat(job.getStoryId()).isEqualTo(100L);
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class,
            names = {"COMPLETED", "FAILED", "CANCELED"})
    void preservesTheTerminalStatusAndVersionAgainstLateUpdates(StoryGenerationJob.Status terminal) {
        StoryGenerationJob job = job();
        switch (terminal) {
            case COMPLETED -> job.complete(100L);
            case FAILED -> job.fail("AI_PROCESSING_FAILED");
            case CANCELED -> job.cancel();
            default -> throw new IllegalArgumentException();
        }

        assertThat(job.getStatusVersion()).isEqualTo(1);
        job.updateProcessing(90);
        job.complete(200L);
        job.fail("LATE_FAILURE");
        job.cancel();

        assertThat(job.getStatus()).isEqualTo(terminal);
        assertThat(job.getStatusVersion()).isEqualTo(1);
        assertThat(job.getStoryId()).isEqualTo(terminal == StoryGenerationJob.Status.COMPLETED ? 100L : null);
        assertThat(job.getErrorCode()).isEqualTo(terminal == StoryGenerationJob.Status.FAILED
                ? "AI_PROCESSING_FAILED" : null);
    }

    @Test
    void invalidInputDoesNotAdvanceTheVersion() {
        StoryGenerationJob job = job();

        assertThatThrownBy(() -> job.updateProcessing(101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.complete(null)).isInstanceOf(IllegalArgumentException.class);

        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(job.getStatusVersion()).isZero();
    }

    @Test
    void timeoutAdvancesTheVersionOnlyOnce() {
        StoryGenerationJob job = job();
        LocalDateTime now = LocalDateTime.of(2026, 10, 29, 12, 0);

        assertThat(job.failIfTimedOut(now.minusHours(1), now)).isTrue();
        assertThat(job.failIfTimedOut(now.minusHours(1), now.plusMinutes(1))).isFalse();
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(job.getStatusVersion()).isEqualTo(1);
    }

    private StoryGenerationJob job() {
        return new StoryGenerationJob("execution", 1L, 2L, Story.Mood.PLAIN);
    }
}

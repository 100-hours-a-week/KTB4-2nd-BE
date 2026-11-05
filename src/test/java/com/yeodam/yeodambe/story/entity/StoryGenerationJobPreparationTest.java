package com.yeodam.yeodambe.story.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryGenerationJobPreparationTest {
    @Test
    void 준비된_ID로_완료한다() {
        var job = new StoryGenerationJob("execution", 1L, 2L, Story.Mood.PLAIN);
        job.prepareStory(10L);
        long version = job.getStatusVersion();

        job.complete(10L);

        assertThat(job.getStoryId()).isEqualTo(10L);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.COMPLETED);
        assertThat(job.getProgress()).isEqualTo(100);
        assertThat(job.getStatusVersion()).isEqualTo(version + 1);
    }

    @Test
    void 다른_ID로_완료하면_상태를_바꾸지_않고_거부한다() {
        var job = new StoryGenerationJob("execution", 1L, 2L, Story.Mood.PLAIN);
        job.updateProcessing(30);
        job.prepareStory(10L);
        long version = job.getStatusVersion();

        assertThatThrownBy(() -> job.complete(20L))
                .isInstanceOf(IllegalStateException.class);

        assertThat(job.getStoryId()).isEqualTo(10L);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.PROCESSING);
        assertThat(job.getProgress()).isEqualTo(30);
        assertThat(job.getStatusVersion()).isEqualTo(version);
    }

    @Test
    void 별도_준비_없이_완료한다() {
        var job = new StoryGenerationJob("execution", 1L, 2L, Story.Mood.PLAIN);

        job.complete(10L);

        assertThat(job.getStoryId()).isEqualTo(10L);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.COMPLETED);
    }

    @Test
    void 완료나_버전_증가_없이_준비된_ID를_유지한다() {
        var job = new StoryGenerationJob("execution", 1L, 2L, Story.Mood.PLAIN);
        job.updateProcessing(30);
        long version = job.getStatusVersion();

        job.prepareStory(10L);
        job.prepareStory(10L);

        assertThat(job.getStoryId()).isEqualTo(10L);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.PROCESSING);
        assertThat(job.getProgress()).isEqualTo(30);
        assertThat(job.getStatusVersion()).isEqualTo(version);
    }

    @Test
    void 다른_ID로_준비하면_기존_ID를_바꾸지_않고_거부한다() {
        var job = new StoryGenerationJob("execution", 1L, 2L, Story.Mood.PLAIN);
        job.prepareStory(10L);

        assertThatThrownBy(() -> job.prepareStory(20L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(job.getStoryId()).isEqualTo(10L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void 유효하지_않은_준비_ID를_거부한다(Long storyId) {
        var job = new StoryGenerationJob("execution", 1L, 2L, Story.Mood.PLAIN);

        assertThatThrownBy(() -> job.prepareStory(storyId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(job.getStoryId()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class,
            names = {"COMPLETED", "FAILED", "CANCELED"})
    void 종료된_작업의_준비를_무시한다(StoryGenerationJob.Status status) {
        var job = new StoryGenerationJob("execution", 1L, 2L, Story.Mood.PLAIN);
        job.prepareStory(10L);
        ReflectionTestUtils.setField(job, "status", status);
        long version = job.getStatusVersion();

        job.prepareStory(20L);

        assertThat(job.getStoryId()).isEqualTo(10L);
        assertThat(job.getStatus()).isEqualTo(status);
        assertThat(job.getStatusVersion()).isEqualTo(version);
    }
}

package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoryGenerationStatusServiceTest {

    @Test
    void returnsTheStoredVersionWithoutAdvancingItOnRepeatedReads() {
        var repository = mock(StoryGenerationJobRepository.class);
        var service = new StoryGenerationStatusService(repository);
        var job = new StoryGenerationJob("execution", 1L, 2L, Story.Mood.PLAIN);
        ReflectionTestUtils.setField(job, "id", 10L);
        job.updateProcessing(30);
        when(repository.findById(10L)).thenReturn(Optional.of(job));

        var first = service.getStatus(2L, 10L);
        var second = service.getStatus(2L, 10L);

        assertThat(first.generationId()).isEqualTo(10L);
        assertThat(first.status()).isEqualTo(StoryGenerationJob.Status.PROCESSING);
        assertThat(first.statusVersion()).isEqualTo(2);
        assertThat(second).isEqualTo(first);
        assertThat(job.getStatusVersion()).isEqualTo(2);

        job.complete(100L);
        var completed = service.getStatus(2L, 10L);
        assertThat(completed.status()).isEqualTo(StoryGenerationJob.Status.COMPLETED);
        assertThat(completed.statusVersion()).isEqualTo(3);
        assertThat(completed.storyId()).isEqualTo(100L);
    }
}

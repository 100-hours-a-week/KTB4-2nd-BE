package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryGenerationResultRecoveryServiceTest {
    private final StoryGenerationJobRepository jobs = mock(StoryGenerationJobRepository.class);
    private final StoryGenerationResultRecoveryService service =
            new StoryGenerationResultRecoveryService(jobs, new ObjectMapper());
    private final String message = "{\"type\":\"story_result\",\"trip_id\":7,\"execution_id\":\"exec-1\"}";

    private StoryGenerationJob prepare() {
        var job = new StoryGenerationJob("exec-1", 7L, 2L, Story.Mood.EMOTIONAL);
        ReflectionTestUtils.setField(job, "id", 1L);
        when(jobs.findByExecutionId("exec-1")).thenReturn(Optional.of(job));
        when(jobs.findByIdForUpdate(1L)).thenReturn(Optional.of(job));
        return job;
    }

    @Test
    void failsMatchingActiveJob() {
        var job = prepare();
        service.failIdentifiedJob(message);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(job.getErrorCode()).isEqualTo("STORY_RESULT_PROCESSING_FAILED");
    }

    @Test
    void mismatchedTripDoesNotFailAnotherJob() {
        var job = prepare();
        service.failIdentifiedJob(message.replace("7", "8"));
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
    }

    @Test
    void completedJobIsNotOverwritten() {
        var job = prepare();
        job.complete(9L);
        service.failIdentifiedJob(message);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.COMPLETED);
        assertThat(job.getStoryId()).isEqualTo(9L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "null", "{}", "{\"type\":\"process_result\"}"})
    void unidentifiedMessageDoesNotModifyJobs(String malformed) {
        service.failIdentifiedJob(malformed);
        verifyNoInteractions(jobs);
    }

    @Test
    void databaseFailureIsPropagatedToPreventRecoveryAcknowledgment() {
        prepare();
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(jobs.findByIdForUpdate(1L)).thenThrow(failure);
        assertThatThrownBy(() -> service.failIdentifiedJob(message)).isSameAs(failure);
    }
}

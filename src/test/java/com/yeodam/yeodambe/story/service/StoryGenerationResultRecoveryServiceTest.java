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
    private final org.springframework.context.ApplicationEventPublisher publisher = mock(org.springframework.context.ApplicationEventPublisher.class);
    private final StoryGenerationResultRecoveryService service =
            new StoryGenerationResultRecoveryService(jobs, new ObjectMapper(), publisher, mock(jakarta.persistence.EntityManager.class), mock(StoryGenerationDiscardService.class));
    private final String message = "{\"type\":\"story_result\",\"trip_id\":7,\"execution_id\":\"exec-1\"}";

    private StoryGenerationJob prepare() {
        var job = new StoryGenerationJob("exec-1", 7L, 2L, Story.Mood.EMOTIONAL);
        ReflectionTestUtils.setField(job, "id", 1L);
        when(jobs.findByExecutionId("exec-1")).thenReturn(Optional.of(job));
        when(jobs.findByIdForUpdate(1L)).thenReturn(Optional.of(job));
        return job;
    }

    @Test
    void 일치하는_활성_작업을_실패_처리한다() {
        var job = prepare();
        service.failIdentifiedJob(message);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(job.getErrorCode()).isEqualTo("STORY_RESULT_PROCESSING_FAILED");
    }

    @Test
    void 여행이_다르면_다른_작업을_실패시키지_않는다() {
        var job = prepare();
        service.failIdentifiedJob(message.replace("7", "8"));
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        verifyNoInteractions(publisher);
    }

    @Test
    void 완료된_작업을_덮어쓰지_않는다() {
        var job = prepare();
        job.complete(9L);
        service.failIdentifiedJob(message);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.COMPLETED);
        assertThat(job.getStoryId()).isEqualTo(9L);
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "null", "{}", "{\"type\":\"process_result\"}"})
    void 식별할_수_없는_메시지는_작업을_수정하지_않는다(String malformed) {
        service.failIdentifiedJob(malformed);
        verifyNoInteractions(jobs, publisher);
    }

    @Test
    void 복구_확인을_막기_위해_DB_실패를_전파한다() {
        prepare();
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(jobs.findByIdForUpdate(1L)).thenThrow(failure);
        assertThatThrownBy(() -> service.failIdentifiedJob(message)).isSameAs(failure);
    }
}

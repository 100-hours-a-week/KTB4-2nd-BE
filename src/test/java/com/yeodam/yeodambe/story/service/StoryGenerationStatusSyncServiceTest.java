package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.client.StoryGenerationAiClient;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.exception.StoryGenerationAiRequestException;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class StoryGenerationStatusSyncServiceTest {
    private final StoryGenerationJobRepository jobs = mock(StoryGenerationJobRepository.class);
    private final StoryGenerationAiClient ai = mock(StoryGenerationAiClient.class);
    private final StoryGenerationProgressService progress = mock(StoryGenerationProgressService.class);
    private final StoryGenerationCompletionService completion = mock(StoryGenerationCompletionService.class);
    private final StoryGenerationTerminalService terminal = mock(StoryGenerationTerminalService.class);
    private final StoryGenerationStatusSyncService service =
            new StoryGenerationStatusSyncService(jobs, ai, progress);
    private final StoryGenerationJob job = new StoryGenerationJob("execution-1", 241L, 2L, Story.Mood.PLAIN);

    @ParameterizedTest
    @EnumSource(StoryGenerationAiResponse.Status.class)
    void httpUpdatesOnlyProcessingAndLeavesTerminalStateForKafka(StoryGenerationAiResponse.Status status) {
        prepareJob();
        var response = response(241L, "execution-1", status);
        when(ai.getStatus(241L)).thenReturn(response);

        service.synchronize(10L);

        verify(ai).getStatus(241L);
        switch (status) {
            case QUEUED, COMPLETED, FAILED, CANCELED -> {
                verifyNoInteractions(progress, completion, terminal);
                assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
                assertThat(job.getStoryId()).isNull();
            }
            case PROCESSING -> verify(progress).updateProcessing(10L, response);
        }
        verifyNoMoreInteractions(progress, completion, terminal);
    }

    @Test
    void queuedResponseDoesNotMoveProcessingJobBackward() {
        prepareJob();
        job.updateProcessing(50);
        when(ai.getStatus(241L)).thenReturn(response(241L, "execution-1", StoryGenerationAiResponse.Status.QUEUED));

        service.synchronize(10L);

        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.PROCESSING);
        assertThat(job.getProgress()).isEqualTo(50);
        verifyNoInteractions(progress, completion, terminal);
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void doesNotCallAiForFinishedJob(StoryGenerationJob.Status status) {
        prepareJob();
        switch (status) {
            case COMPLETED -> job.complete(99L);
            case FAILED -> job.fail("WORKER_DEAD");
            case CANCELED -> job.cancel();
            default -> throw new AssertionError("Unexpected test status");
        }

        service.synchronize(10L);

        verifyNoInteractions(ai, progress, completion, terminal);
    }

    @Test
    void taskNotFoundLeavesJobForLaterQueryWithoutImmediateRetry() {
        prepareJob();
        when(ai.getStatus(241L)).thenThrow(httpFailure(404, "TASK_NOT_FOUND"));

        assertThatCode(() -> service.synchronize(10L)).doesNotThrowAnyException();

        verify(ai).getStatus(241L);
        verifyNoMoreInteractions(ai);
        verifyNoInteractions(progress, completion, terminal);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
    }

    @ParameterizedTest
    @MethodSource("otherHttpFailures")
    void propagatesLookupFailureWithoutFailingJob(Integer statusCode, String errorCode) {
        prepareJob();
        var failure = httpFailure(statusCode, errorCode);
        when(ai.getStatus(241L)).thenThrow(failure);

        assertThatThrownBy(() -> service.synchronize(10L)).isSameAs(failure);

        verifyNoInteractions(progress, completion, terminal);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
    }

    static Stream<Arguments> otherHttpFailures() {
        return Stream.of(Arguments.of(404, "OTHER_ERROR"), Arguments.of(404, null),
                Arguments.of(500, "TASK_NOT_FOUND"), Arguments.of(401, "UNAUTHORIZED"),
                Arguments.of(null, null));
    }

    @ParameterizedTest
    @MethodSource("invalidIdentities")
    void rejectsWrongOrMissingExecutionReferenceBeforeDispatch(Long tripId, String executionId) {
        prepareJob();
        when(ai.getStatus(241L)).thenReturn(response(tripId, executionId, StoryGenerationAiResponse.Status.COMPLETED));

        assertThatThrownBy(() -> service.synchronize(10L)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH.message());
        verifyNoInteractions(progress, completion, terminal);
    }

    static Stream<Arguments> invalidIdentities() {
        return Stream.of(Arguments.of(242L, "execution-1"), Arguments.of(241L, "old-execution"),
                Arguments.of(null, "execution-1"), Arguments.of(241L, null));
    }

    @Test
    void rejectsMissingResponseOrStatus() {
        prepareJob();
        when(ai.getStatus(241L)).thenReturn(null, response(241L, "execution-1", null));

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.synchronize(10L)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID.message());
        }
        verifyNoInteractions(progress, completion, terminal);
    }

    @Test
    void missingJobDoesNotCallAi() {
        when(jobs.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.synchronize(10L)).isInstanceOf(IllegalStateException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message());
        verifyNoInteractions(ai, progress, completion, terminal);
    }

    @Test
    void propagatesProgressSaveFailure() {
        prepareJob();
        var response = response(241L, "execution-1", StoryGenerationAiResponse.Status.PROCESSING);
        when(ai.getStatus(241L)).thenReturn(response);
        var failure = new IllegalArgumentException("invalid result");
        org.mockito.Mockito.doThrow(failure).when(progress).updateProcessing(10L, response);

        assertThatThrownBy(() -> service.synchronize(10L)).isSameAs(failure);
        verifyNoInteractions(completion, terminal);
    }

    private void prepareJob() {
        when(jobs.findById(10L)).thenReturn(Optional.of(job));
    }

    private static StoryGenerationAiRequestException httpFailure(Integer status, String code) {
        return new StoryGenerationAiRequestException(StoryGenerationInternalErrorMessage.AI_STATUS_LOOKUP_FAILED,
                status, code);
    }

    private static StoryGenerationAiResponse response(Long tripId, String executionId,
                                                     StoryGenerationAiResponse.Status status) {
        return new StoryGenerationAiResponse(tripId, executionId, status, null, null, null, null);
    }
}

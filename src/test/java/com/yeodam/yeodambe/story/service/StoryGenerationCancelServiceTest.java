package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryGenerationCannotBeCanceledException;
import com.yeodam.yeodambe.common.exception.StoryGenerationNotFoundException;
import com.yeodam.yeodambe.story.client.StoryGenerationAiClient;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.exception.StoryGenerationAiRequestException;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StoryGenerationCancelServiceTest {
    private final StoryGenerationJobRepository jobs = mock(StoryGenerationJobRepository.class);
    private final StoryGenerationAiClient ai = mock(StoryGenerationAiClient.class);
    private final StoryGenerationCancelService service = new StoryGenerationCancelService(jobs, ai, new StoryGenerationTerminalService(jobs, mock(org.springframework.context.ApplicationEventPublisher.class)));

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class, names = {"QUEUED", "PROCESSING"})
    void cancellationAckImmediatelyFinishesJob(StoryGenerationJob.Status status) {
        var job = prepare(42L);
        if (status == StoryGenerationJob.Status.PROCESSING) {
            job.updateProcessing(50);
        }
        when(ai.cancel(77L)).thenReturn(response(77L, "execution-1", StoryGenerationAiResponse.Status.CANCELED));

        service.cancel(42L, 7L);

        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.CANCELED);
        assertThat(job.getStoryId()).isNull();
        verify(ai).cancel(77L);
        verify(jobs, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void finishedJobIsRejectedBeforeAiRequest(StoryGenerationJob.Status status) {
        var job = prepare(42L);
        switch (status) {
            case COMPLETED -> job.complete(99L);
            case FAILED -> job.fail("WORKER_DEAD");
            case CANCELED -> job.cancel();
            default -> throw new AssertionError(status);
        }

        assertThatThrownBy(() -> service.cancel(42L, 7L))
                .isInstanceOf(StoryGenerationCannotBeCanceledException.class);
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingOrOtherOwnerJobIsRejectedBeforeAiRequest(boolean missing) {
        if (missing) {
            when(jobs.findById(7L)).thenReturn(Optional.empty());
        } else {
            prepare(999L);
        }

        assertThatThrownBy(() -> service.cancel(42L, 7L))
                .isInstanceOf(StoryGenerationNotFoundException.class);
        verifyNoInteractions(ai);
    }

    @Test
    void aiAlreadyFinishedErrorMapsToCancellationConflict() {
        prepare(42L);
        when(ai.cancel(77L)).thenThrow(new StoryGenerationAiRequestException(
                StoryGenerationInternalErrorMessage.AI_CANCEL_REQUEST_FAILED, 409, "TASK_NOT_CANCELABLE"));

        assertThatThrownBy(() -> service.cancel(42L, 7L))
                .isInstanceOf(StoryGenerationCannotBeCanceledException.class);
    }

    @Test
    void connectionFailureRemainsAnErrorInsteadOfSuccessfulCancellation() {
        var job = prepare(42L);
        var failure = new StoryGenerationAiRequestException(
                StoryGenerationInternalErrorMessage.AI_CANCEL_REQUEST_FAILED, null, null);
        when(ai.cancel(77L)).thenThrow(failure);

        assertThatThrownBy(() -> service.cancel(42L, 7L)).isSameAs(failure);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationAiResponse.Status.class, names = {"QUEUED", "PROCESSING", "COMPLETED", "FAILED"})
    void nonCancellationAckIsRejected(StoryGenerationAiResponse.Status status) {
        prepare(42L);
        when(ai.cancel(77L)).thenReturn(response(77L, "execution-1", status));

        assertThatThrownBy(() -> service.cancel(42L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID.message());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void differentTripOrExecutionAckIsRejected(boolean differentTrip) {
        prepare(42L);
        when(ai.cancel(77L)).thenReturn(response(differentTrip ? 88L : 77L,
                differentTrip ? "execution-1" : "execution-2", StoryGenerationAiResponse.Status.CANCELED));

        assertThatThrownBy(() -> service.cancel(42L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH.message());
    }

    @Test
    void aiTaskNotFoundAlsoFinishesJob() {
        var job = prepare(42L);
        when(ai.cancel(77L)).thenThrow(new StoryGenerationAiRequestException(
                StoryGenerationInternalErrorMessage.AI_CANCEL_REQUEST_FAILED, 404, "TASK_NOT_FOUND"));

        service.cancel(42L, 7L);

        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.CANCELED);
        verify(ai).cancel(77L);
    }

    @Test
    void unrelatedAiNotFoundDoesNotCancelJob() {
        var job = prepare(42L);
        var failure = new StoryGenerationAiRequestException(
                StoryGenerationInternalErrorMessage.AI_CANCEL_REQUEST_FAILED, 404, "OTHER_NOT_FOUND");
        when(ai.cancel(77L)).thenThrow(failure);

        assertThatThrownBy(() -> service.cancel(42L, 7L)).isSameAs(failure);

        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        verify(jobs, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void completionDuringAiCallIsNotReplacedByCancellation() {
        var job = prepare(42L);
        when(ai.cancel(77L)).thenAnswer(invocation -> {
            job.complete(99L);
            return response(77L, "execution-1", StoryGenerationAiResponse.Status.CANCELED);
        });

        assertThatThrownBy(() -> service.cancel(42L, 7L))
                .isInstanceOf(StoryGenerationCannotBeCanceledException.class);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.COMPLETED);
        assertThat(job.getStoryId()).isEqualTo(99L);
    }

    private StoryGenerationJob prepare(Long ownerId) {
        var job = new StoryGenerationJob("execution-1", 77L, ownerId, Story.Mood.EMOTIONAL);
        when(jobs.findById(7L)).thenReturn(Optional.of(job));
        when(jobs.findByIdForUpdate(7L)).thenReturn(Optional.of(job));
        return job;
    }

    private StoryGenerationAiResponse response(Long tripId, String executionId, StoryGenerationAiResponse.Status status) {
        return new StoryGenerationAiResponse(tripId, executionId, status, null, null, null, null);
    }
}

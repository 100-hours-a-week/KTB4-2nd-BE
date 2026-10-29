package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.common.exception.StoryGenerationCannotBeCanceledException;
import com.yeodam.yeodambe.common.exception.StoryGenerationNotFoundException;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse.Failure;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse.Status;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StoryGenerationTerminalServiceTest {
    private final StoryGenerationJobRepository repository = mock(StoryGenerationJobRepository.class);
    private final org.springframework.context.ApplicationEventPublisher eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
    private final StoryGenerationTerminalService service = new StoryGenerationTerminalService(repository, eventPublisher);
    private final StoryGenerationJob job = new StoryGenerationJob("execution-1", 241L, 2L, Story.Mood.PLAIN);

    @Test
    void storesActualAiFailureCodeWithoutChangingProgressToOneHundred() {
        prepareJob();
        job.updateProcessing(50);

        service.apply(10L, response(241L, "execution-1", Status.FAILED, failure("WORKER_DEAD")));

        verify(repository).findByIdForUpdate(10L);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(job.getErrorCode()).isEqualTo("WORKER_DEAD");
        assertThat(job.getProgress()).isEqualTo(50);
        assertThat(job.getStoryId()).isNull();
    }

    @Test
    void cancellationDoesNotRequireFailureObject() {
        prepareJob();

        service.apply(10L, response(241L, "execution-1", Status.CANCELED, null));

        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.CANCELED);
        assertThat(job.getErrorCode()).isNull();
        assertThat(job.getProgress()).isZero();
    }

    @Test
    void acceptsErrorCodeAtDatabaseLengthBoundary() {
        prepareJob();

        service.apply(10L, response(241L, "execution-1", Status.FAILED, failure("A".repeat(100))));

        assertThat(job.getErrorCode()).hasSize(100);
    }

    @ParameterizedTest
    @MethodSource("invalidFailures")
    void rejectsMissingOrOversizedErrorCodeWithoutChangingJob(Failure failure) {
        prepareJob();

        assertThatThrownBy(() -> service.apply(10L, response(241L, "execution-1", Status.FAILED, failure)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID.message());
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(job.getErrorCode()).isNull();
    }

    static Stream<Failure> invalidFailures() {
        return Stream.of(null, failure(null), failure(" "), failure("A".repeat(101)));
    }

    @ParameterizedTest
    @MethodSource("invalidIdentities")
    void rejectsDifferentTripOrExecution(Long tripId, String executionId) {
        prepareJob();

        assertThatThrownBy(() -> service.apply(10L, response(tripId, executionId, Status.CANCELED, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH.message());
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
    }

    static Stream<Arguments> invalidIdentities() {
        return Stream.of(Arguments.of(242L, "execution-1"), Arguments.of(241L, "old-execution"),
                Arguments.of(null, "execution-1"), Arguments.of(241L, null));
    }

    @ParameterizedTest
    @MethodSource("lateTerminalResponses")
    void doesNotReplaceAnAlreadyFinishedState(StoryGenerationJob.Status oldStatus, Status receivedStatus) {
        prepareJob();
        switch (oldStatus) {
            case COMPLETED -> job.complete(99L);
            case FAILED -> job.fail("OLD_ERROR");
            case CANCELED -> job.cancel();
            default -> throw new AssertionError("Unexpected test status");
        }
        int progress = job.getProgress();
        Long storyId = job.getStoryId();
        String errorCode = job.getErrorCode();

        service.apply(10L, response(241L, "execution-1", receivedStatus, failure("NEW_ERROR")));

        assertThat(job.getStatus()).isEqualTo(oldStatus);
        assertThat(job.getProgress()).isEqualTo(progress);
        assertThat(job.getStoryId()).isEqualTo(storyId);
        assertThat(job.getErrorCode()).isEqualTo(errorCode);
        verifyNoInteractions(eventPublisher);
    }

    static Stream<Arguments> lateTerminalResponses() {
        return Stream.of(StoryGenerationJob.Status.COMPLETED, StoryGenerationJob.Status.FAILED,
                        StoryGenerationJob.Status.CANCELED)
                .flatMap(old -> Stream.of(Status.FAILED, Status.CANCELED).map(received -> Arguments.of(old, received)));
    }

    @ParameterizedTest
    @MethodSource("otherResponses")
    void rejectsNonTerminalResponsesBeforeLookingUpJob(StoryGenerationAiResponse response) {
        assertThatThrownBy(() -> service.apply(10L, response)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, eventPublisher);
    }

    static Stream<StoryGenerationAiResponse> otherResponses() {
        return Stream.of(null, response(241L, "execution-1", null, null),
                response(241L, "execution-1", Status.QUEUED, null),
                response(241L, "execution-1", Status.PROCESSING, null),
                response(241L, "execution-1", Status.COMPLETED, null));
    }

    @Test
    void rejectsMissingJob() {
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(10L, response(241L, "execution-1", Status.CANCELED, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message());
    }

    @Test
    void confirmsCancellationAndAcceptsLaterKafkaCancellation() {
        prepareJob();
        job.updateProcessing(50);

        service.confirmCancellation(2L, 10L);
        service.apply(10L, response(241L, "execution-1", Status.CANCELED, null));

        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.CANCELED);
        assertThat(job.getProgress()).isEqualTo(50);
        assertThat(job.getStoryId()).isNull();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = StoryGenerationJob.Status.class,
            names = {"COMPLETED", "FAILED"})
    void cancellationDoesNotReplaceCompletionOrFailure(StoryGenerationJob.Status status) {
        prepareJob();
        if (status == StoryGenerationJob.Status.COMPLETED) {
            job.complete(99L);
        } else {
            job.fail("WORKER_DEAD");
        }

        assertThatThrownBy(() -> service.confirmCancellation(2L, 10L))
                .isInstanceOf(StoryGenerationCannotBeCanceledException.class);
        assertThat(job.getStatus()).isEqualTo(status);
    }

    @Test
    void cancellationRejectsOtherOwnerWithoutChangingState() {
        prepareJob();

        assertThatThrownBy(() -> service.confirmCancellation(999L, 10L))
                .isInstanceOf(StoryGenerationNotFoundException.class);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
    }

    @Test
    void cancellationRejectsMissingJob() {
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.confirmCancellation(2L, 10L))
                .isInstanceOf(StoryGenerationNotFoundException.class);
    }

    private void prepareJob() {
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(job));
    }

    private static Failure failure(String code) {
        return new Failure(code, "AI diagnostic message", null);
    }

    private static StoryGenerationAiResponse response(Long tripId, String executionId, Status status, Failure error) {
        return new StoryGenerationAiResponse(tripId, executionId, status, null, null, null, error);
    }
}

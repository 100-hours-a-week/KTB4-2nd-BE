package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StoryGenerationProgressServiceTest {
    private final StoryGenerationJobRepository jobRepository = mock(StoryGenerationJobRepository.class);
    private final StoryGenerationProgressService service = new StoryGenerationProgressService(jobRepository);
    private final StoryGenerationJob job = new StoryGenerationJob("execution-1", 241L, 2L, Story.Mood.PLAIN);

    @Test
    void updatesProcessingStatusAndCalculatedPercentageUsingLockQuery() {
        prepareJob();

        service.updateProcessing(10L, response(241L, "execution-1", new StoryGenerationAiResponse.Progress(2, 4)));

        verify(jobRepository).findByIdForUpdate(10L);
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.PROCESSING);
        assertThat(job.getProgress()).isEqualTo(50);
    }

    @Test
    void truncatesFractionalPercentage() {
        prepareJob();

        service.updateProcessing(10L, response(241L, "execution-1", new StoryGenerationAiResponse.Progress(1, 3)));

        assertThat(job.getProgress()).isEqualTo(33);
    }

    @Test
    void handlesLargeCountsWithoutIntegerMultiplicationOverflow() {
        prepareJob();

        service.updateProcessing(10L, response(241L, "execution-1",
                new StoryGenerationAiResponse.Progress(Integer.MAX_VALUE, Integer.MAX_VALUE)));

        assertThat(job.getProgress()).isEqualTo(100);
    }

    @Test
    void lateLowerProgressDoesNotMoveProgressBackward() {
        prepareJob();
        job.updateProcessing(75);

        service.updateProcessing(10L, response(241L, "execution-1", new StoryGenerationAiResponse.Progress(1, 4)));

        assertThat(job.getProgress()).isEqualTo(75);
    }

    @Test
    void rejectsMissingJob() {
        when(jobRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProcessing(10L,
                response(241L, "execution-1", new StoryGenerationAiResponse.Progress(2, 4))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message());
    }

    @ParameterizedTest
    @MethodSource("invalidIdentities")
    void rejectsDifferentTripOrExecutionWithoutChangingJob(Long tripId, String executionId) {
        prepareJob();

        assertThatThrownBy(() -> service.updateProcessing(10L,
                response(tripId, executionId, new StoryGenerationAiResponse.Progress(2, 4))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH.message());
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(job.getProgress()).isZero();
    }

    static Stream<Arguments> invalidIdentities() {
        return Stream.of(Arguments.of(242L, "execution-1"), Arguments.of(241L, "old-execution"),
                Arguments.of(null, "execution-1"), Arguments.of(241L, null));
    }

    @ParameterizedTest
    @MethodSource("invalidProgress")
    void rejectsInvalidCountsWithoutChangingJob(StoryGenerationAiResponse.Progress progress) {
        prepareJob();

        assertThatThrownBy(() -> service.updateProcessing(10L, response(241L, "execution-1", progress)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.JOB_PROGRESS_INVALID.message());
        assertThat(job.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(job.getProgress()).isZero();
    }

    static Stream<StoryGenerationAiResponse.Progress> invalidProgress() {
        return Stream.of(null, new StoryGenerationAiResponse.Progress(null, 4),
                new StoryGenerationAiResponse.Progress(1, null),
                new StoryGenerationAiResponse.Progress(-1, 4),
                new StoryGenerationAiResponse.Progress(0, 0),
                new StoryGenerationAiResponse.Progress(1, -1),
                new StoryGenerationAiResponse.Progress(5, 4));
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void ignoresLateProcessingResponseForFinishedJob(StoryGenerationJob.Status status) {
        prepareJob();
        job.updateProcessing(75);
        switch (status) {
            case COMPLETED -> job.complete(99L);
            case FAILED -> job.fail("WORKER_DEAD");
            case CANCELED -> job.cancel();
            default -> throw new AssertionError("Unexpected test status");
        }
        int oldProgress = job.getProgress();
        String oldError = job.getErrorCode();
        Long oldStoryId = job.getStoryId();

        service.updateProcessing(10L, response(241L, "execution-1", new StoryGenerationAiResponse.Progress(2, 4)));

        assertThat(job.getStatus()).isEqualTo(status);
        assertThat(job.getProgress()).isEqualTo(oldProgress);
        assertThat(job.getErrorCode()).isEqualTo(oldError);
        assertThat(job.getStoryId()).isEqualTo(oldStoryId);
    }

    @ParameterizedTest
    @MethodSource("nonProcessingResponses")
    void rejectsOtherStatusesBeforeAccessingDatabase(StoryGenerationAiResponse response) {
        assertThatThrownBy(() -> service.updateProcessing(10L, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID.message());
        verifyNoInteractions(jobRepository);
    }

    static Stream<StoryGenerationAiResponse> nonProcessingResponses() {
        return Stream.concat(Stream.of((StoryGenerationAiResponse) null),
                Stream.of(StoryGenerationAiResponse.Status.QUEUED, StoryGenerationAiResponse.Status.COMPLETED,
                                StoryGenerationAiResponse.Status.FAILED, StoryGenerationAiResponse.Status.CANCELED)
                        .map(status -> new StoryGenerationAiResponse(241L, "execution-1", status,
                                null, null, null, null)));
    }

    private void prepareJob() {
        when(jobRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(job));
    }

    private static StoryGenerationAiResponse response(Long tripId, String executionId,
                                                     StoryGenerationAiResponse.Progress progress) {
        return new StoryGenerationAiResponse(tripId, executionId, StoryGenerationAiResponse.Status.PROCESSING,
                progress, "WRITING", null, null);
    }
}

package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;

import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.exception.InvalidStoryResultException;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.common.exception.StoryGenerationCannotBeCanceledException;
import com.yeodam.yeodambe.common.exception.StoryGenerationNotFoundException;

@Service
@RequiredArgsConstructor
public class StoryGenerationTerminalService {
    private final StoryGenerationJobRepository jobRepository;

    @Transactional
    public void apply(
            Long generationId,
            StoryGenerationAiResponse response
    ) {
        if (response == null
                || (response.status() != StoryGenerationAiResponse.Status.FAILED
                && response.status() != StoryGenerationAiResponse.Status.CANCELED)) {
            throw new InvalidStoryResultException(
                    StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID
            );
        }

        var job = jobRepository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message()
                ));

        if (!job.getTripId().equals(response.tripId())
                || !job.getExecutionId().equals(response.executionId())) {
            throw new InvalidStoryResultException(
                    StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH
            );
        }

        if (job.isFinished()) {
            return;
        }

        if (response.status() == StoryGenerationAiResponse.Status.CANCELED) {
            job.cancel();
            logWorkflowFinished(job, "canceled", null);
            return;
        }

        var error = response.error();

        if (error == null
                || error.code() == null
                || error.code().isBlank()
                || error.code().length() > 100) {
            throw new InvalidStoryResultException(
                    StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID
            );
        }

        job.fail(error.code());
        logWorkflowFinished(job, "failure", job.getErrorCode());
    }

    @Transactional
    public void confirmCancellation(Long userId, Long generationId) {
        var job = jobRepository.findByIdForUpdate(generationId)
                .filter(saved -> saved.getUserId().equals(userId))
                .orElseThrow(StoryGenerationNotFoundException::new);

        if (job.getStatus() == StoryGenerationJob.Status.CANCELED) {
            return;
        }

        if (job.isFinished()) {
            throw new StoryGenerationCannotBeCanceledException();
        }

        job.cancel();

        WorkflowLog.finished(
                "story_generation",
                job.getTripId(),
                job.getExecutionId(),
                job.getCreatedAt(),
                "canceled",
                null
        );
    }

    private void logWorkflowFinished(StoryGenerationJob job, String result, String errorCode) {
        WorkflowLog.event("story_generation", job.getTripId(), job.getExecutionId(), "ai_result_received");
        WorkflowLog.finished("story_generation", job.getTripId(), job.getExecutionId(),
                job.getCreatedAt(), result, errorCode);
    }
}
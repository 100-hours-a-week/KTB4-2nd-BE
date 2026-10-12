package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryGenerationCannotBeCanceledException;
import com.yeodam.yeodambe.common.exception.StoryGenerationNotFoundException;
import com.yeodam.yeodambe.story.client.StoryGenerationAiClient;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.exception.StoryGenerationAiRequestException;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoryGenerationCancelService {

    private final StoryGenerationJobRepository jobRepository;
    private final StoryGenerationAiClient aiClient;

    public void cancel(Long userId, Long generationId) {
        var job = jobRepository.findById(generationId)
                .filter(saved -> saved.getUserId().equals(userId))
                .orElseThrow(StoryGenerationNotFoundException::new);

        if (job.isFinished()) {
            throw new StoryGenerationCannotBeCanceledException();
        }

        StoryGenerationAiResponse response;

        try {
            response = aiClient.cancel(job.getTripId());
        } catch (StoryGenerationAiRequestException failure) {
            if (Integer.valueOf(409).equals(failure.getStatusCode())
                    && "TASK_NOT_CANCELABLE".equals(failure.getErrorCode())) {
                throw new StoryGenerationCannotBeCanceledException();
            }
            throw failure;
        }

        if (!job.getTripId().equals(response.tripId())
                || !job.getExecutionId().equals(response.executionId())) {
            throw new IllegalArgumentException(
                    StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH.message()
            );
        }

        if (response.status() != StoryGenerationAiResponse.Status.CANCELED) {
            throw new IllegalArgumentException(
                    StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID.message()
            );
        }
    }
}

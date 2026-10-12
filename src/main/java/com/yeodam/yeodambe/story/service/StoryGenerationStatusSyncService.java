package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.client.StoryGenerationAiClient;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.exception.StoryGenerationAiRequestException;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoryGenerationStatusSyncService {
    private final StoryGenerationJobRepository jobRepository;
    private final StoryGenerationAiClient aiClient;
    private final StoryGenerationProgressService progressService;

    public void synchronize(Long generationId) {
        var job = jobRepository.findById(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message()
                ));

        if (job.isFinished()) {
            return;
        }

        StoryGenerationAiResponse response;

        try {
            response = aiClient.getStatus(job.getTripId());
        } catch (StoryGenerationAiRequestException failure) {
            if (Integer.valueOf(404).equals(failure.getStatusCode())
                    && "TASK_NOT_FOUND".equals(failure.getErrorCode())) {
                return;
            }

            throw failure;
        }

        if (response == null || response.status() == null) {
            throw new IllegalArgumentException(
                    StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID.message()
            );
        }

        if (!job.getTripId().equals(response.tripId())
                || !job.getExecutionId().equals(response.executionId())) {
            throw new IllegalArgumentException(
                    StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH.message()
            );
        }

        switch (response.status()) {
            case PROCESSING ->
                    progressService.updateProcessing(generationId, response);
            case QUEUED, COMPLETED, FAILED, CANCELED -> {
            }
        }
    }
}
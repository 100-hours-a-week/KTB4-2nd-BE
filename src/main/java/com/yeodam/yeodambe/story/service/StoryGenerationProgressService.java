package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoryGenerationProgressService {
    private final StoryGenerationJobRepository jobRepository;

    @Transactional
    public void updateProcessing(
            Long generationId,
            StoryGenerationAiResponse response
    ) {
        if (response == null
                || response.status() != StoryGenerationAiResponse.Status.PROCESSING) {
            throw new IllegalArgumentException(
                    StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID.message()
            );
        }

        var job = jobRepository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message()
                ));

        if (!job.getTripId().equals(response.tripId())
                || !job.getExecutionId().equals(response.executionId())) {
            throw new IllegalArgumentException(
                    StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH.message()
            );
        }

        if (job.isFinished()) {
            return;
        }

        var progress = response.progress();

        if (progress == null
                || progress.done() == null
                || progress.total() == null
                || progress.done() < 0
                || progress.total() <= 0
                || progress.done() > progress.total()) {
            throw new IllegalArgumentException(
                    StoryGenerationInternalErrorMessage.JOB_PROGRESS_INVALID.message()
            );
        }

        int percentage = (int) (100L * progress.done() / progress.total());
        job.updateProcessing(percentage);
    }
}
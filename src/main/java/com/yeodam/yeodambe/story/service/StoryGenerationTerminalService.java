package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.exception.InvalidStoryResultException;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                    StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID.message()
            );
        }

        var job = jobRepository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message()
                ));

        if (!job.getTripId().equals(response.tripId())
                || !job.getExecutionId().equals(response.executionId())) {
            throw new InvalidStoryResultException(
                    StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH.message()
            );
        }

        if (job.isFinished()) {
            return;
        }

        if (response.status() == StoryGenerationAiResponse.Status.CANCELED) {
            job.cancel();
            return;
        }

        var error = response.error();

        if (error == null
                || error.code() == null
                || error.code().isBlank()
                || error.code().length() > 100) {
            throw new InvalidStoryResultException(
                    StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID.message()
            );
        }

        job.fail(error.code());
    }
}
package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.common.exception.StoryGenerationNotFoundException;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.service.response.StoryGenerationStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoryGenerationStatusService {

    private final StoryGenerationJobRepository jobRepository;

    @Transactional(readOnly = true)
    public StoryGenerationStatusResponse getStatus(
            Long userId,
            Long generationId
    ) {
        var job = jobRepository.findById(generationId)
                .filter(saved -> saved.getUserId().equals(userId))
                .orElseThrow(StoryGenerationNotFoundException::new);

        StoryGenerationStatusResponse.Failure error = null;

        if (job.getStatus() == StoryGenerationJob.Status.FAILED) {
            error = new StoryGenerationStatusResponse.Failure(
                    ErrorMessage.STORY_GENERATION_FAILED.name(),
                    StoryGenerationInternalErrorMessage.GENERATION_FAILED.message()
            );
        }

        return new StoryGenerationStatusResponse(
                job.getId(),
                job.getStatus(),
                job.getStoryId(),
                error
        );
    }
}
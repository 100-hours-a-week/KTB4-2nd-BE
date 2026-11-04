package com.yeodam.yeodambe.story.service.response;

import com.yeodam.yeodambe.story.entity.StoryGenerationJob;

public record StoryGenerationStatusResponse(
        Long generationId,
        StoryGenerationJob.Status status,
        long statusVersion,
        Long storyId,
        Failure error
) {
    public StoryGenerationStatusResponse {
        if (status != StoryGenerationJob.Status.COMPLETED) {
            storyId = null;
        }
    }

    public record Failure(
            String code,
            String message
    ) {
    }
}

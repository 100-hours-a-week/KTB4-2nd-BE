package com.yeodam.yeodambe.story.service.response;

import com.yeodam.yeodambe.story.entity.StoryGenerationJob;

public record StoryGenerationStatusResponse(
        Long generationId,
        StoryGenerationJob.Status status,
        int progress,
        Long storyId,
        Failure error
) {
    public record Failure(
            String code,
            String message
    ) {
    }
}
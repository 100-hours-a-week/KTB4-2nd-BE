package com.yeodam.yeodambe.story.service.response;

import com.yeodam.yeodambe.story.entity.StoryGenerationJob;

public record StoryGenerationStatusResponse(
        Long generationId,
        StoryGenerationJob.Status status,
        long statusVersion,
        Long storyId,
        Failure error
) {
    public record Failure(
            String code,
            String message
    ) {
    }
}

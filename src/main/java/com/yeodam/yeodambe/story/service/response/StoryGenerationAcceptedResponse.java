package com.yeodam.yeodambe.story.service.response;

import com.yeodam.yeodambe.story.entity.StoryGenerationJob;

public record StoryGenerationAcceptedResponse(
        Long generationId,
        Long tripId,
        StoryGenerationJob.Status status
) {
}

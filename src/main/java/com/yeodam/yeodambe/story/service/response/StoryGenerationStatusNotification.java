package com.yeodam.yeodambe.story.service.response;

import com.yeodam.yeodambe.story.entity.StoryGenerationJob;

import java.time.Instant;
import java.util.UUID;

public record StoryGenerationStatusNotification(
        UUID eventId,
        Instant occurredAt,
        Long tripId,
        Long generationId,
        long statusVersion,
        StoryGenerationJob.Status status,
        Long storyId,
        StoryGenerationStatusResponse.Failure error
) {
}

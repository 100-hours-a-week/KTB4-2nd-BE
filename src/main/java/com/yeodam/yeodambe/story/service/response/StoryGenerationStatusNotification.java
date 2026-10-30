package com.yeodam.yeodambe.story.service.response;

import com.yeodam.yeodambe.story.entity.StoryGenerationJob;

import com.yeodam.yeodambe.common.websocket.service.request.AiTaskCompletionType;
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
        StoryGenerationStatusResponse.Failure error,
        AiTaskCompletionType eventType,
        StoryCompletionListItem listItem
) {
}

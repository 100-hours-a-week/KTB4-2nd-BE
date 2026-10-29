package com.yeodam.yeodambe.story.event;

import com.yeodam.yeodambe.story.service.response.StoryGenerationStatusResponse;

public record StoryGenerationStatusChangedEvent(
        Long userId,
        Long tripId,
        StoryGenerationStatusResponse snapshot
) {
}

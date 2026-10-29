package com.yeodam.yeodambe.story.event;

import com.yeodam.yeodambe.story.service.response.StoryGenerationStatusResponse;
import com.yeodam.yeodambe.story.service.response.StoryCompletionListItem;

public record StoryGenerationStatusChangedEvent(
        Long userId,
        Long tripId,
        StoryGenerationStatusResponse snapshot,
        StoryCompletionListItem listItem
) {
    public StoryGenerationStatusChangedEvent(Long userId, Long tripId, StoryGenerationStatusResponse snapshot) {
        this(userId, tripId, snapshot, null);
    }
}

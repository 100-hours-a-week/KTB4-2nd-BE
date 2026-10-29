package com.yeodam.yeodambe.story.service.response;

import com.yeodam.yeodambe.story.entity.Story;

public record StoryCompletionListItem(
        Long storyId,
        Long tripId,
        long sequence,
        Story.Mood mood,
        String storySummary
) {
}

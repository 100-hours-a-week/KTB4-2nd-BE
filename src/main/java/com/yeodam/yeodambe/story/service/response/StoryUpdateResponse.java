package com.yeodam.yeodambe.story.service.response;

import com.yeodam.yeodambe.story.entity.Story;

import java.util.List;

public record StoryUpdateResponse(
        Long storyId,
        Story.Mood tone,
        String storySummary,
        List<StoryDetailResponse.Day> days
) {
    public static StoryUpdateResponse from(
            StoryDetailResponse detail
    ) {
        return new StoryUpdateResponse(
                detail.storyId(),
                detail.mood(),
                detail.storySummary(),
                detail.days()
        );
    }
}

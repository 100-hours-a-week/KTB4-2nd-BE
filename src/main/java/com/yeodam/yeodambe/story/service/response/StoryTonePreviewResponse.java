package com.yeodam.yeodambe.story.service.response;

import com.yeodam.yeodambe.story.entity.Story;

import java.util.List;

public record StoryTonePreviewResponse(
        Long storyId,
        Story.Mood tone,
        String storySummary,
        List<StoryDetailResponse.Day> days
) {
}
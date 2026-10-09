package com.yeodam.yeodambe.story.service.response;

import com.yeodam.yeodambe.story.entity.Story;

import java.time.LocalDate;
import java.util.List;

public record StoryDetailResponse(
        Long storyId,
        Long tripId,
        boolean userByMe,
        Story.Mood mood,
        String storySummary,
        List<Day> days
) {
    public record Day(
            LocalDate date,
            String dayLabel,
            List<Block> blocks
    ) {
    }

    public record Block(
            Long storyBlockId,
            int orderNumber,
            Long tripPlaceId,
            Long tripAttachmentId,
            String thumbnailUrl,
            String detailSummary,
            String memo
    ) {
    }
}

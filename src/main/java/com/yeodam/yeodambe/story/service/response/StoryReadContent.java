package com.yeodam.yeodambe.story.service.response;

import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.entity.Story;
import org.springframework.data.annotation.Id;

import java.util.List;

public record StoryReadContent(
        @Id Long id,
        Story.Mood mood,
        String storySummary,
        List<StoryContentBlock> blocks
) {
}

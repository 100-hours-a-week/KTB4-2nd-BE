package com.yeodam.yeodambe.story.service.response;

import java.util.List;

public record StoryPreviewKeyBackfillBatch(
        Long nextAfterStoryId,
        int scanned,
        int updated,
        int unchanged,
        List<Long> conflictStoryIds,
        List<Long> invalidStoryIds
) {
}

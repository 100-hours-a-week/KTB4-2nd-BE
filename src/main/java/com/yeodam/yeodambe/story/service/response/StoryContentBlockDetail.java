package com.yeodam.yeodambe.story.service.response;

import com.yeodam.yeodambe.story.document.StoryContentBlock;

import java.time.LocalDateTime;

public record StoryContentBlockDetail(
        StoryContentBlock block,
        Long attachmentId,
        Long attachmentTripId,
        LocalDateTime attachmentDeletedAt,
        Long fileId,
        LocalDateTime fileDeletedAt,
        Long placeId,
        Long placeTripId,
        String currentPreviewStorageKey
) {
}

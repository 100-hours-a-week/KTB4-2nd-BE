package com.yeodam.yeodambe.story.service.response;

import java.util.List;

public record StorySourceFolderListResponse(
        List<Item> items,
        boolean hasNext,
        String nextCursor
) {
    public record Item(
            Long tripPlaceId,
            String placeName,
            long attachmentCount,
            String thumbnailUrl,
            boolean selected
    ) {
    }
}

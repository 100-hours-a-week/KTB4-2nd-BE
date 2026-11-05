package com.yeodam.yeodambe.trip.service.response;

import java.time.LocalDateTime;
import java.util.List;

public record AttachmentPageData(
        List<Item> items,
        boolean hasNext,
        String nextCursor,
        long sequence
) {
    public AttachmentPageData {
        items = List.copyOf(items);
    }

    public record Item(
            Long tripAttachmentId,
            LocalDateTime createdAt,
            String previewStorageKey
    ) {
    }
}

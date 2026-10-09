package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.AttachmentIssue;

import java.util.List;

public record UnclassifiedAttachmentListResponse(
        AttachmentIssue issue,
        String name,
        long attachmentCount,
        List<Item> items,
        boolean hasNext,
        String nextCursor
) {
    public record Item(
            Long tripAttachmentId,
            String thumbnailUrl,
            AttachmentIssue issue,
            Long restoreTripPlaceId
    ) {
    }
}

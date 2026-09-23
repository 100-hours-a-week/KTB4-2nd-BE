package com.yeodam.yeodambe.trip.service.response;

import java.util.List;

public record TripAttachmentListResponse(
        List<Item> items,
        boolean hasNext,
        String nextCursor
) {
    public record Item(
            Long tripAttachmentId,
            String thumbnailUrl
    ) {
    }
}
package com.yeodam.yeodambe.trip.service.response;

import java.util.List;

public record TripAttachmentListResponse(
        List<Item> items,
        boolean hasNext,
        String nextCursor,
        long sequence
) {

    public TripAttachmentListResponse {
        items = List.copyOf(items);
    }

    public record Item(
            Long tripAttachmentId,
            String thumbnailUrl
    ) {
    }
}
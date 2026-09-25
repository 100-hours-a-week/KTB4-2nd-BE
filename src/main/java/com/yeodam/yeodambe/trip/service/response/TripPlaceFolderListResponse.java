package com.yeodam.yeodambe.trip.service.response;

import java.util.List;

public record TripPlaceFolderListResponse(
        List<Item> items,
        boolean hasNext,
        String nextCursor
) {
    public record Item(
            Long tripPlaceId,
            String placeName,
            long attachmentCount,
            String thumbnailUrl
    ) {
    }
}

package com.yeodam.yeodambe.trip.service.response;

import java.util.List;

public record TripPhotoDeletionResult(
        List<Long> attachmentIds,
        long attachmentCount,
        String thumbnailKey,
        List<PlaceChange> places
) {
    public record PlaceChange(
            Long tripPlaceId,
            boolean deleted,
            long attachmentCount,
            String thumbnailKey
    ) {
    }
}

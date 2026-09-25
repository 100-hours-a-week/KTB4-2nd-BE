package com.yeodam.yeodambe.trip.service.response;

import java.time.LocalDate;
import java.util.List;

public record TripDetailResponse(
        Long tripId,
        String tripName,
        LocalDate startDate,
        LocalDate endDate,
        long nightCount,
        List<Region> regions,
        long attachmentCount,
        boolean hasStory,
        boolean isFavorite
) {
    public record Region(
            Long regionId,
            String regionCode,
            String regionName
    ) {
    }
}

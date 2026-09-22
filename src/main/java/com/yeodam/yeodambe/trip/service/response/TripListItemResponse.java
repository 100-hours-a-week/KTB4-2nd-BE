package com.yeodam.yeodambe.trip.service.response;

import java.time.LocalDate;

public record TripListItemResponse(
        Long tripId,
        String tripName,
        LocalDate startDate,
        LocalDate endDate,
        String placeSummary,
        long attachmentCount,
        boolean isFavorite,
        String thumbnailUrl
) {
}

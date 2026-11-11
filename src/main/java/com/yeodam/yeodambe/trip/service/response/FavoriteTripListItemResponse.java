package com.yeodam.yeodambe.trip.service.response;

import java.time.LocalDate;

public record FavoriteTripListItemResponse(
        Long tripId,
        String tripName,
        LocalDate startDate,
        LocalDate endDate,
        String placeSummary,
        long attachmentCount,
        String thumbnailUrl,
        boolean isFavorite
) {
}

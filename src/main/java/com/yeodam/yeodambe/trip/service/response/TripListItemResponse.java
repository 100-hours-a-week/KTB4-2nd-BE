package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import java.time.LocalDate;

public record TripListItemResponse(
        Long tripId,
        ProcessingStatus status,
        String tripName,
        LocalDate startDate,
        LocalDate endDate,
        String placeSummary,
        long attachmentCount,
        boolean isFavorite,
        String thumbnailUrl
) {
}

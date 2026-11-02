package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.TripSharePermission;

import java.time.LocalDate;
import java.util.List;

public record InvitedTripListResponse(
        long totalCount,
        List<Item> items,
        boolean hasNext,
        String nextCursor
) {
    public record Item(
            Long tripId,
            String tripName,
            LocalDate startDate,
            LocalDate endDate,
            String placeSummary,
            long attachmentCount,
            String thumbnailUrl,
            TripSharePermission permission
    ) {}
}

package com.yeodam.yeodambe.trip.service.response;

import java.math.BigDecimal;
import java.util.List;

public record TripMapResponse(
        List<Marker> markers
) {
    public record Marker(
            String regionCode,
            String regionName,
            BigDecimal latitude,
            BigDecimal longitude,
            int tripCount,
            List<TripSummary> trips
    ) {
    }

    public record TripSummary(
            Long tripId,
            String tripName,
            String thumbnailUrl,
            long attachmentCount
    ) {
    }
}
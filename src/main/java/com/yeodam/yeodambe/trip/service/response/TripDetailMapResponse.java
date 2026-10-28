package com.yeodam.yeodambe.trip.service.response;

import java.math.BigDecimal;
import java.util.List;

public record TripDetailMapResponse(
        Long tripId,
        List<Marker> markers
) {
    public record Marker(
            Long tripPlaceId,
            String placeName,
            BigDecimal latitude,
            BigDecimal longitude,
            long attachmentCount,
            String thumbnailUrl
    ) {
    }
}

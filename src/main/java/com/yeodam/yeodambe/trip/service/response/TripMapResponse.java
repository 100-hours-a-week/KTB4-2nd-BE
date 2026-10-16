package com.yeodam.yeodambe.trip.service.response;

import java.math.BigDecimal;
import java.util.List;

public record TripMapResponse(List<Marker> markers) {
    public record Marker(
            Long tripId,
            String tripName,
            BigDecimal latitude,
            BigDecimal longitude,
            String thumbnailUrl,
            long attachmentCount
    ) {
    }
}

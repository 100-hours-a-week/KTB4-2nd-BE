package com.yeodam.yeodambe.trip.repository;

import java.math.BigDecimal;

public record TripDetailMapMarker(
        Long tripPlaceId,
        String placeName,
        BigDecimal latitude,
        BigDecimal longitude,
        String thumbnailKey,
        long attachmentCount
) {
}

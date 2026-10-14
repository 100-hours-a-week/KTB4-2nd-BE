package com.yeodam.yeodambe.trip.repository;

import java.math.BigDecimal;

public record TripMapRepresentative(
        Long tripId,
        String tripName,
        BigDecimal latitude,
        BigDecimal longitude,
        String previewStorageKey
) {
}

package com.yeodam.yeodambe.trip.service.response;

import java.math.BigDecimal;
import java.util.List;

public record TripMapCacheData(List<Marker> markers) {

    public record Marker(
            Long tripId,
            String tripName,
            BigDecimal latitude,
            BigDecimal longitude,
            String previewStorageKey,
            long attachmentCount
    ) {
    }
}

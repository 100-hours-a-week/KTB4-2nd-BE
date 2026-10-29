package com.yeodam.yeodambe.trip.event;

import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;

public record TripProcessingStatusChangedEvent(
        Long userId,
        TripProcessingStateSnapshot snapshot
) {
}
package com.yeodam.yeodambe.trip.event;

import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripCompletionSnapshot;

public record TripProcessingStatusChangedEvent(
        Long userId,
        TripProcessingStateSnapshot snapshot,
        TripCompletionSnapshot completion
) {
    public TripProcessingStatusChangedEvent(Long userId, TripProcessingStateSnapshot snapshot) {
        this(userId, snapshot, null);
    }
}
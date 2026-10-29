package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.TripDisplayStatus;

public record TripProcessingStateSnapshot(
        Long tripId,
        String executionId,
        long statusVersion,
        TripDisplayStatus displayStatus,
        String currentStep,
        String errorCode
) {
}

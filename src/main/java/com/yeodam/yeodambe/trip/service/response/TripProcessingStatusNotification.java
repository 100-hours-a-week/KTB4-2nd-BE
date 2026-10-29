package com.yeodam.yeodambe.trip.service.response;

import java.time.Instant;
import java.util.UUID;

public record TripProcessingStatusNotification(
        UUID eventId,
        Instant occurredAt,
        Long tripId,
        String executionId,
        long statusVersion,
        TripProcessingStatusResponse.Status status,
        String currentStep,
        TripProcessingStatusResponse.Result result,
        TripProcessingStatusResponse.Error error
) {
}

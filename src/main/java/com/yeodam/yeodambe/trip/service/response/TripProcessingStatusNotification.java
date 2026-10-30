package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.common.websocket.service.request.AiTaskCompletionType;
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
        TripProcessingStatusResponse.Error error,
        AiTaskCompletionType eventType,
        TripListItemResponse listItem
) {
}

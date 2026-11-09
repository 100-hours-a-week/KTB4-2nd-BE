package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.common.websocket.service.request.AiTaskCompletionType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;

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
        TripListItemResponse listItem,
        @JsonInclude(JsonInclude.Include.NON_NULL) String analysisExecutionId,
        @JsonInclude(JsonInclude.Include.NON_NULL) TripAnalysisExecution.Status analysisState,
        @JsonInclude(JsonInclude.Include.NON_NULL) String blockedReason
) {
    public TripProcessingStatusNotification(
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
        this(eventId, occurredAt, tripId, executionId, statusVersion, status, currentStep, result, error,
                eventType, listItem, null, null, null);
    }
}

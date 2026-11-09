package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.TripDisplayStatus;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;

public record TripProcessingStateSnapshot(
        Long tripId,
        String executionId,
        long statusVersion,
        TripDisplayStatus displayStatus,
        String currentStep,
        String errorCode,
        String completedRawPayload,
        String analysisExecutionId,
        TripAnalysisExecution.Status analysisState,
        String blockedReason,
        boolean notifyParticipants
) {
    public TripProcessingStateSnapshot(
            Long tripId,
            String executionId,
            long statusVersion,
            TripDisplayStatus displayStatus,
            String currentStep,
            String errorCode,
            String completedRawPayload,
            String analysisExecutionId,
            TripAnalysisExecution.Status analysisState,
            String blockedReason
    ) {
        this(tripId, executionId, statusVersion, displayStatus, currentStep, errorCode,
                completedRawPayload, analysisExecutionId, analysisState, blockedReason, true);
    }

    public TripProcessingStateSnapshot(
            Long tripId,
            String executionId,
            long statusVersion,
            TripDisplayStatus displayStatus,
            String currentStep,
            String errorCode,
            String completedRawPayload
    ) {
        this(tripId, executionId, statusVersion, displayStatus, currentStep, errorCode,
                completedRawPayload, null, null, null, false);
    }

    public TripProcessingStateSnapshot(
            Long tripId,
            String executionId,
            long statusVersion,
            TripDisplayStatus displayStatus,
            String currentStep,
            String errorCode
    ) {
        this(tripId, executionId, statusVersion, displayStatus, currentStep, errorCode, null);
    }
}

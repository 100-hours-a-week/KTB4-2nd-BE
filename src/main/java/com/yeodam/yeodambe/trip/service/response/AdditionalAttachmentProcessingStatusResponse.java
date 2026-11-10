package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;

public record AdditionalAttachmentProcessingStatusResponse(
        Long tripId,
        String additionId,
        long statusVersion,
        TripProcessingStatusResponse.Status status,
        String currentStep,
        String analysisExecutionId,
        TripAnalysisExecution.Status analysisState,
        boolean includedInAnalysis,
        int analysisFailureCount,
        boolean automaticRetryPending,
        String blockedReason
) {
}

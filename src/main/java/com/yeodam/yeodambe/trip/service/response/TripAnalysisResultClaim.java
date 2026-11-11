package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.integration.service.response.WorkflowTraceCarrier;

public record TripAnalysisResultClaim(
        String executionId,
        Long tripId,
        Long userId,
        String claimToken,
        String rawPayload,
        int attempt,
        WorkflowTraceCarrier traceContext
) {
}

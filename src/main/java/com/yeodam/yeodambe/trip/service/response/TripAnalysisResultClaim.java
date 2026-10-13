package com.yeodam.yeodambe.trip.service.response;

public record TripAnalysisResultClaim(
        String executionId,
        Long tripId,
        Long userId,
        String claimToken,
        String rawPayload,
        int attempt
) {
}

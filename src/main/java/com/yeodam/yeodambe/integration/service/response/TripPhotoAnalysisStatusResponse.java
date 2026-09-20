package com.yeodam.yeodambe.integration.service.response;

import tools.jackson.databind.JsonNode;

public record TripPhotoAnalysisStatusResponse(
        Long tripId,
        Status status,
        Progress progress,
        String currentStep,
        JsonNode result,
        JsonNode error
) {
    public enum Status {
        PROCESSING,
        COMPLETED,
        FAILED,
        CANCELED
    }

    public record Progress(int done, int total) {
    }
}

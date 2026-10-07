package com.yeodam.yeodambe.integration.service.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

public record QueuePhotoAnalysisStatusResponse(
        @JsonProperty("trip_id") Long tripId,
        @JsonProperty("execution_id") String executionId,
        TripPhotoAnalysisStatusResponse.Status status,
        TripPhotoAnalysisStatusResponse.Progress progress,
        @JsonProperty("current_step") String currentStep,
        JsonNode result,
        JsonNode error
) {}

package com.yeodam.yeodambe.trip.service.response;

import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;

public record TripProcessingStatusResponse(
        Long tripId,
        String executionId,
        long statusVersion,
        Status status,
        String currentStep,
        Result result,
        Error error,
        @JsonInclude(JsonInclude.Include.NON_NULL) String analysisExecutionId,
        @JsonInclude(JsonInclude.Include.NON_NULL) TripAnalysisExecution.Status analysisState,
        @JsonInclude(JsonInclude.Include.NON_NULL) String blockedReason
) {
    public TripProcessingStatusResponse(
            Long tripId,
            String executionId,
            long statusVersion,
            Status status,
            String currentStep,
            Result result,
            Error error
    ) {
        this(tripId, executionId, statusVersion, status, currentStep, result, error, null, null, null);
    }

    public TripProcessingStatusResponse withAnalysis(TripAnalysisExecution execution) {
        return new TripProcessingStatusResponse(
                tripId,
                executionId,
                statusVersion,
                status,
                currentStep,
                result,
                error,
                execution.getExecutionId(),
                execution.getStatus(),
                execution.getStatus() == TripAnalysisExecution.Status.APPLY_BLOCKED ? execution.getErrorCode() : null
        );
    }

    public enum Status {
        PROCESSING, FINALIZING, COMPLETED, FAILED, CANCELED
    }

    public record Result(
            Long tripId,
            int placeFolderCount,
            int classifiedAttachmentCount,
            int unclassifiedAttachmentCount
    ) {
        public static Result fromAiResult(Long tripId, JsonNode result) {
            int classified = 0;
            for (JsonNode place : result.path("places")) {
                classified = Math.addExact(classified, place.path("attachments").size());
            }
            return new Result(tripId, result.path("places").size(), classified, result.path("unclassified").size());
        }
    }

    public record Error(
            String code,
            String message
    ) {
    }
}

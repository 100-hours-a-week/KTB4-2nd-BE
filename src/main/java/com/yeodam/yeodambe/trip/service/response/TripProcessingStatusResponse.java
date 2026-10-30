package com.yeodam.yeodambe.trip.service.response;

import tools.jackson.databind.JsonNode;

public record TripProcessingStatusResponse(
        Long tripId,
        String executionId,
        long statusVersion,
        Status status,
        String currentStep,
        Result result,
        Error error
) {
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
            for (var place : result.path("places")) {
                classified = Math.addExact(classified, place.path("attachments").size());
            }
            return new Result(tripId, result.path("places").size(), classified, result.path("unclassified").size());
        }
    }

    public record Error(String code, String message) {
    }
}

package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.integration.exception.IntegrationInternalErrorMessage;
import com.yeodam.yeodambe.integration.service.response.QueuePhotoAnalysisStatusResponse;
import com.yeodam.yeodambe.integration.service.response.TripPhotoAnalysisStatusResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class QueuePhotoAnalysisResponseMapper {
    private QueuePhotoAnalysisResponseMapper() {}

    public static QueuePhotoAnalysisStatusResponse parse(
            Long tripId, String executionId, JsonNode response
    ) {
        if (executionId == null || executionId.isBlank() || response == null
                || !response.path("execution_id").isString()
                || !executionId.equals(response.path("execution_id").asString())) {
            throw invalidResponse();
        }
        TripPhotoAnalysisStatusResponse status = TripPhotoAnalysisService.validateStatusResponse(tripId, response);
        return new QueuePhotoAnalysisStatusResponse(tripId, executionId, status.status(),
                status.progress(), status.currentStep(), status.result(), status.error());
    }

    public static JsonNode completedResult(QueuePhotoAnalysisStatusResponse response, ObjectMapper json) {
        if (response == null || response.status() != TripPhotoAnalysisStatusResponse.Status.COMPLETED
                || response.result() == null || !response.result().path("places").isArray()
                || !response.result().path("unclassified").isArray()) {
            throw invalidResponse();
        }
        var result = json.createObjectNode();
        result.put("trip_id", response.tripId());
        result.put("execution_id", response.executionId());
        result.set("places", response.result().path("places"));
        result.set("unclassified", response.result().path("unclassified"));
        return result;
    }

    private static IllegalStateException invalidResponse() {
        return new IllegalStateException(IntegrationInternalErrorMessage.AI_PHOTO_ANALYSIS_STATUS_INVALID.message());
    }
}

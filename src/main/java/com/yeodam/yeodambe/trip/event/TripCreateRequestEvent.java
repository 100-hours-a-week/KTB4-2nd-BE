package com.yeodam.yeodambe.trip.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yeodam.yeodambe.common.event.EventPayload;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import tools.jackson.databind.JsonNode;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripCreateRequestEvent(
        String type,
        @JsonProperty("trip_id") Long tripId,
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("batch_no") Integer batchNo,
        String trigger,
        @JsonProperty("trip_name") String tripName,
        TripPhotoAnalysisRequest.Period period,
        List<TripPhotoAnalysisRequest.Region> regions,
        JsonNode attachments
) implements EventPayload {
    @Override
    public EventType eventType() {
        return EventType.TRIP_CREATE_REQUEST;
    }
}

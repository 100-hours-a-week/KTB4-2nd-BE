package com.yeodam.yeodambe.trip.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yeodam.yeodambe.common.event.EventPayload;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import tools.jackson.databind.JsonNode;

public record TripCreateResultEvent(
        String type,
        @JsonProperty("trip_id") Long tripId,
        @JsonProperty("execution_id") String executionId,
        Status status,
        JsonNode result,
        JsonNode error
) implements EventPayload {
    public enum Status {
        COMPLETED, FAILED, CANCELED
    }

    public TripCreateResultEvent {
        boolean completed = status == Status.COMPLETED;
        boolean failed = status == Status.FAILED;
        if (!"process_result".equals(type) || tripId == null || tripId < 1
                || executionId == null || executionId.isBlank() || executionId.length() > 36
                || status == null
                || completed != (result != null && !result.isNull())
                || failed != (error != null && !error.isNull())
                || (completed && !result.isObject()) || (failed && !error.isObject())) {
            throw new IllegalArgumentException(TripInternalErrorMessage.TRIP_RESULT_EVENT_STRUCTURE_INVALID.message());
        }
    }

    @Override
    public EventType eventType() {
        return EventType.TRIP_CREATE_RESULT;
    }
}

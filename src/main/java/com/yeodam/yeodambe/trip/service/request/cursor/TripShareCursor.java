package com.yeodam.yeodambe.trip.service.request.cursor;

import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.util.CursorCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record TripShareCursor(Long tripId, Long lastTripShareId) {
    public TripShareCursor {
        if (tripId == null || tripId <= 0 || lastTripShareId == null || lastTripShareId <= 0) {
            throw new InvalidCursorException();
        }
    }

    public String encode(ObjectMapper objectMapper) {
        try {
            return CursorCodec.encode(this, objectMapper);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.TRIP_SHARE_CURSOR_ENCODE_FAILED.message(), exception);
        }
    }

    public static TripShareCursor decode(String value, Long expectedTripId, ObjectMapper objectMapper) {
        if (value == null) {
            return null;
        }

        TripShareCursor cursor;
        try {
            JsonNode node = CursorCodec.decode(value, objectMapper);
            validateJsonFields(node);
            cursor = objectMapper.treeToValue(node, TripShareCursor.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidCursorException();
        }

        if (cursor == null || !cursor.tripId().equals(expectedTripId)) {
            throw new InvalidCursorException();
        }
        return cursor;
    }

    private static void validateJsonFields(JsonNode node) {
        if (!node.path("tripId").isIntegralNumber()
                || !node.path("tripId").canConvertToLong()
                || !node.path("lastTripShareId").isIntegralNumber()
                || !node.path("lastTripShareId").canConvertToLong()) {
            throw new InvalidCursorException();
        }
    }
}

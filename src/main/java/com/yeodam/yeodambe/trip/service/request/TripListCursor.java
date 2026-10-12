package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.exception.InvalidTripListFilterException;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.util.CursorCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

public record TripListCursor(
        TripSort sort,
        boolean favorite,
        boolean favoriteGroup,
        LocalDate startDate,
        long tripId
) {
    public TripListCursor {
        if (sort == null || startDate == null || tripId <= 0) {
            throw new InvalidTripListFilterException();
        }
    }

    public String encode(ObjectMapper objectMapper) {
        try {
            return CursorCodec.encode(this, objectMapper);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.TRIP_LIST_CURSOR_ENCODE_FAILED.message(),
                    exception
            );
        }
    }

    public static TripListCursor decode(String value, ObjectMapper objectMapper) {
        try {
            JsonNode node = CursorCodec.decode(value, objectMapper);
            validateJsonFields(node);
            return objectMapper.treeToValue(node, TripListCursor.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidTripListFilterException();
        }
    }

    private static void validateJsonFields(JsonNode node) {
        if (!node.path("sort").isString()
                || !node.path("favorite").isBoolean()
                || !node.path("favoriteGroup").isBoolean()
                || !node.path("startDate").isString()
                || !node.path("tripId").isIntegralNumber()
                || !node.path("tripId").canConvertToLong()) {
            throw new InvalidTripListFilterException();
        }
    }
}

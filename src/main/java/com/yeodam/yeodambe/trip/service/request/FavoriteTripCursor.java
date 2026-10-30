package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.util.CursorCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

public record FavoriteTripCursor(
        LocalDate startDate,
        long tripId
) {
    public FavoriteTripCursor {
        if (startDate == null || tripId <= 0) {
            throw new InvalidCursorException();
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

    public static FavoriteTripCursor decode(
            String value,
            ObjectMapper objectMapper
    ) {
        if (value == null) {
            return null;
        }

        try {
            JsonNode node = CursorCodec.decode(value, objectMapper);
            if (!node.path("startDate").isString()
                    || !node.path("tripId").isIntegralNumber()
                    || !node.path("tripId").canConvertToLong()) {
                throw new InvalidCursorException();
            }
            return objectMapper.treeToValue(node, FavoriteTripCursor.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidCursorException();
        }
    }
}

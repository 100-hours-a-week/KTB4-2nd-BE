package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.util.CursorCodec;
import com.yeodam.yeodambe.common.exception.InvalidPlaceFolderCursorException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;


public record PlaceFolderCursor(
        Long tripId,
        String placeName,
        Long tripPlaceId
) {
    public PlaceFolderCursor {
        if (tripId == null || tripId <= 0
                || placeName == null || placeName.isBlank() || placeName.length() > 50
                || tripPlaceId == null || tripPlaceId <= 0) {
            throw new InvalidPlaceFolderCursorException();
        }
    }

    public String encode(ObjectMapper objectMapper) {
        try {
            return CursorCodec.encode(this, objectMapper);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.PLACE_FOLDER_CURSOR_ENCODE_FAILED.message(),
                    exception
            );
        }
    }

    public static PlaceFolderCursor decode(
            String value,
            Long expectedTripId,
            ObjectMapper objectMapper
    ) {
        if (value == null) {
            return null;
        }

        PlaceFolderCursor cursor;
        try {
            cursor = objectMapper.treeToValue(
                    CursorCodec.decode(value, objectMapper),
                    PlaceFolderCursor.class
            );
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidPlaceFolderCursorException();
        }

        if (cursor == null || !cursor.tripId().equals(expectedTripId)) {
            throw new InvalidPlaceFolderCursorException();
        }

        return cursor;
    }
}

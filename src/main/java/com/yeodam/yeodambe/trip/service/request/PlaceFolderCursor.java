package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.exception.InvalidPlaceFolderCursorException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;

public record PlaceFolderCursor(Long tripId, String placeName, Long tripPlaceId) {
    public PlaceFolderCursor {
        if (tripId == null || tripId <= 0
                || placeName == null || placeName.isBlank() || placeName.length() > 50
                || tripPlaceId == null || tripPlaceId <= 0) {
            throw new InvalidPlaceFolderCursorException();
        }
    }

    public String encode(ObjectMapper objectMapper) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(this));
        } catch (JacksonException exception) {
            throw new IllegalStateException("장소 폴더 목록 커서를 생성할 수 없습니다.", exception);
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
        if (value.isBlank()) {
            throw new InvalidPlaceFolderCursorException();
        }

        PlaceFolderCursor cursor;
        try {
            cursor = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(value),
                    PlaceFolderCursor.class
            );
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidPlaceFolderCursorException();
        }

        if (cursor == null || !cursor.tripId().equals(expectedTripId)) {
            throw new InvalidPlaceFolderCursorException();
        }

        return cursor;    }
}

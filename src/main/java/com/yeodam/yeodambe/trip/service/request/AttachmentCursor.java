package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.util.CursorCodec;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Objects;

public record AttachmentCursor(
        LocalDateTime createdAt,
        Long tripAttachmentId,
        Long tripId,
        Long tripPlaceId,
        AttachmentIssue issue
) {
    public AttachmentCursor {
        if (createdAt == null || tripAttachmentId == null || tripAttachmentId <= 0
                || tripId == null || tripId <= 0
                || (tripPlaceId != null && tripPlaceId <= 0)
                || issue == AttachmentIssue.NONE
                || (tripPlaceId != null && issue != null)) {
            throw new InvalidCursorException();
        }
    }

    public String encode(ObjectMapper objectMapper) {
        try {
            return CursorCodec.encode(this, objectMapper);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.ATTACHMENT_CURSOR_ENCODE_FAILED.message(),
                    exception
            );
        }
    }

    public static AttachmentCursor decode(
            String value,
            Long expectedTripId,
            Long expectedTripPlaceId,
            AttachmentIssue expectedIssue,
            ObjectMapper objectMapper
    ) {
        if (value == null) {
            return null;
        }

        AttachmentCursor cursor;
        try {
            JsonNode node = CursorCodec.decode(value, objectMapper);
            validateJsonFields(node);
            cursor = objectMapper.treeToValue(node, AttachmentCursor.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidCursorException();
        }

        if (!cursor.tripId().equals(expectedTripId)
                || !Objects.equals(cursor.tripPlaceId(), expectedTripPlaceId)
                || cursor.issue() != expectedIssue) {
            throw new InvalidCursorException();
        }
        return cursor;
    }

    private static void validateJsonFields(JsonNode node) {
        JsonNode tripPlaceId = node.path("tripPlaceId");
        JsonNode issue = node.path("issue");
        if (!node.path("createdAt").isString()
                || !node.path("tripAttachmentId").isIntegralNumber()
                || !node.path("tripAttachmentId").canConvertToLong()
                || !node.path("tripId").isIntegralNumber()
                || !node.path("tripId").canConvertToLong()
                || !(tripPlaceId.isNull()
                        || (tripPlaceId.isIntegralNumber() && tripPlaceId.canConvertToLong()))
                || !(issue.isNull() || issue.isString())) {
            throw new InvalidCursorException();
        }
        if (issue.isString()) {
            AttachmentIssue.valueOf(issue.asString());
        }
    }
}

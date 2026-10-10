package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.util.CursorCodec;
import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

public record AttachmentCursor(
        LocalDateTime createdAt,
        Long tripAttachmentId
) {
    public AttachmentCursor {
        if (createdAt == null || tripAttachmentId == null || tripAttachmentId <= 0) {
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
            ObjectMapper objectMapper
    ) {
        if (value == null) {
            return null;
        }

        try {
            return objectMapper.treeToValue(
                    CursorCodec.decode(value, objectMapper),
                    AttachmentCursor.class
            );
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidCursorException();
        }
    }
}

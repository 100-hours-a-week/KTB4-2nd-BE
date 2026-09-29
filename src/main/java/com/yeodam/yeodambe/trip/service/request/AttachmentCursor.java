package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Base64;

public record AttachmentCursor(
        LocalDateTime createdAt,
        Long tripAttachmentId
) {
    public AttachmentCursor {
        if (createdAt == null || tripAttachmentId == null) {
            throw new InvalidCursorException();
        }
    }

    public String encode(ObjectMapper objectMapper) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(this));
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "첨부 목록 커서를 생성할 수 없습니다.",
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

        if (value.isBlank()) {
            throw new InvalidCursorException();
        }

        try {
            return objectMapper.readValue(
                    Base64.getUrlDecoder().decode(value),
                    AttachmentCursor.class
            );
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidCursorException();
        }
    }
}
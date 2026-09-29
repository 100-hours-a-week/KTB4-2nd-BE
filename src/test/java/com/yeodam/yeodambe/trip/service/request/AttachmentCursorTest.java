package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentCursorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 커서를_URL_safe_Base64로_왕복한다() {
        AttachmentCursor cursor = new AttachmentCursor(
                LocalDateTime.of(2026, 9, 29, 12, 0),
                18L
        );

        String encoded = cursor.encode(objectMapper);
        AttachmentCursor decoded = AttachmentCursor.decode(encoded, objectMapper);

        assertThat(decoded).isEqualTo(cursor);
        assertThat(encoded).doesNotContain("+", "/", "=");
    }

    @Test
    void 생략한_커서는_첫_페이지로_처리한다() {
        assertThat(AttachmentCursor.decode(null, objectMapper)).isNull();
    }

    @Test
    void 빈값_손상값_필수필드가_없는_커서는_거부한다() {
        assertInvalid("");
        assertInvalid("   ");
        assertInvalid("not-base64!");
        assertInvalid(encodedJson("{not-json"));
        assertInvalid(encodedJson("{\"tripAttachmentId\":18}"));
        assertInvalid(encodedJson("{\"createdAt\":\"2026-09-29T12:00:00\"}"));
    }

    private void assertInvalid(String cursor) {
        assertThatThrownBy(() -> AttachmentCursor.decode(cursor, objectMapper))
                .isInstanceOf(InvalidCursorException.class);
    }

    private String encodedJson(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}

package com.yeodam.yeodambe.trip.util;

import com.yeodam.yeodambe.trip.service.request.AttachmentCursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void JSON_객체를_URL_safe_Base64로_왕복한다() {
        AttachmentCursor cursor = new AttachmentCursor(
                LocalDateTime.of(2026, 10, 9, 12, 0), 18L
        );

        String encoded = CursorCodec.encode(cursor, objectMapper);
        var node = CursorCodec.decode(encoded, objectMapper);

        assertThat(encoded).doesNotContain("+", "/", "=");
        assertThat(objectMapper.treeToValue(node, AttachmentCursor.class)).isEqualTo(cursor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "18", "true", "\"cursor\"", "{not-json"})
    void JSON_객체가_아니거나_손상되면_거절한다(String json) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> CursorCodec.decode(encoded, objectMapper))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-base64!"})
    void 빈값이나_손상된_Base64를_거절한다(String value) {
        assertThatThrownBy(() -> CursorCodec.decode(value, objectMapper))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.yeodam.yeodambe.trip.service.request.cursor;

import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripShareCursorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 여행과_마지막_공유_ID를_URL_safe_커서로_보존한다() {
        TripShareCursor cursor = new TripShareCursor(7L, 105L);

        String encoded = cursor.encode(objectMapper);

        assertThat(encoded).doesNotContain("+", "/", "=");
        assertThat(TripShareCursor.decode(encoded, 7L, objectMapper)).isEqualTo(cursor);
        assertThat(TripShareCursor.decode(
                "eyJ0cmlwSWQiOjcsImxhc3RUcmlwU2hhcmVJZCI6MTA1fQ", 7L, objectMapper))
                .isEqualTo(new TripShareCursor(7L, 105L));
    }

    @Test
    void 생략한_커서는_첫_페이지다() {
        assertThat(TripShareCursor.decode(null, 7L, objectMapper)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-base64!"})
    void 빈값과_손상된_Base64를_거절한다(String value) {
        assertThatThrownBy(() -> TripShareCursor.decode(value, 7L, objectMapper))
                .isInstanceOf(InvalidCursorException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "{broken", "{\"tripId\":7}", "{\"lastTripShareId\":105}"
    })
    void 잘못된_JSON과_필드_누락을_거절한다(String json) {
        assertInvalid(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1", "1.5", "\"7\"", "true", "[]", "{}", "9223372036854775808"})
    void 두_ID는_양의_long_정수여야_한다(String value) {
        assertInvalid("{\"tripId\":" + value + ",\"lastTripShareId\":105}");
        assertInvalid("{\"tripId\":7,\"lastTripShareId\":" + value + "}");
    }

    @Test
    void 다른_여행이나_여행_범위가_없는_커서를_거절한다() {
        String encoded = new TripShareCursor(7L, 105L).encode(objectMapper);

        assertThatThrownBy(() -> TripShareCursor.decode(encoded, 8L, objectMapper))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> TripShareCursor.decode(encoded, null, objectMapper))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void 직접_생성해도_잘못된_ID를_거절한다() {
        assertThatThrownBy(() -> new TripShareCursor(null, 105L)).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> new TripShareCursor(7L, null)).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> new TripShareCursor(0L, 105L)).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> new TripShareCursor(7L, -1L)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void 서버_직렬화_실패는_입력_오류로_처리하지_않는다() {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        JacksonException failure = mock(JacksonException.class);
        when(failingMapper.writeValueAsBytes(any())).thenThrow(failure);

        assertThatThrownBy(() -> new TripShareCursor(7L, 105L).encode(failingMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(failure);
    }

    private void assertInvalid(String json) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> TripShareCursor.decode(encoded, 7L, objectMapper))
                .isInstanceOf(InvalidCursorException.class);
    }
}

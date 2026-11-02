package com.yeodam.yeodambe.trip.service.request.cursor;

import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class FavoriteTripCursorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 날짜와_ID를_문자열로_왕복하고_첫_페이지는_null을_허용한다() {
        FavoriteTripCursor cursor = new FavoriteTripCursor(LocalDate.of(2026, 10, 30), 42L);
        assertThat(FavoriteTripCursor.decode(cursor.encode(objectMapper), objectMapper)).isEqualTo(cursor);
        assertThat(FavoriteTripCursor.decode(null, objectMapper)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-base64!"})
    void 빈값과_손상된_문자열을_거절한다(String value) {
        assertThatThrownBy(() -> FavoriteTripCursor.decode(value, objectMapper))
                .isInstanceOf(InvalidCursorException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "{broken",
            "{\"startDate\":\"bad-date\",\"tripId\":1}",
            "{\"startDate\":null,\"tripId\":1}",
            "{\"startDate\":\"2026-10-30\"}",
            "{\"startDate\":\"2026-10-30\",\"tripId\":0}",
            "{\"startDate\":\"2026-10-30\",\"tripId\":-1}",
            "{\"startDate\":\"2026-10-30\",\"tripId\":1.5}",
            "{\"startDate\":\"2026-10-30\",\"tripId\":\"1\"}",
            "{\"startDate\":\"2026-10-30\",\"tripId\":9223372036854775808}"
    })
    void 필수값과_형식과_ID_범위를_검사한다(String json) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> FavoriteTripCursor.decode(encoded, objectMapper))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void 기존에_발급한_커서_문자열을_해석한다() {
        assertThat(FavoriteTripCursor.decode("eyJzdGFydERhdGUiOiIyMDI2LTEwLTMwIiwidHJpcElkIjo0Mn0", objectMapper))
                .isEqualTo(new FavoriteTripCursor(LocalDate.of(2026, 10, 30), 42L));
    }
}

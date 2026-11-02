package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvitedTripCursorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 날짜와_최대_ID를_왕복하고_생략한_커서는_첫_페이지이다() {
        InvitedTripCursor cursor = new InvitedTripCursor(LocalDate.of(2026, 11, 20), Long.MAX_VALUE);

        String encoded = cursor.encode(objectMapper);

        assertThat(encoded).doesNotContain("=", "+", "/");
        assertThat(InvitedTripCursor.decode(encoded, objectMapper)).isEqualTo(cursor);
        assertThat(InvitedTripCursor.decode(null, objectMapper)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-base64!"})
    void 빈값과_손상된_커서를_거절한다(String value) {
        assertThatThrownBy(() -> InvitedTripCursor.decode(value, objectMapper))
                .isInstanceOf(InvalidCursorException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "{broken",
            "{\"startDate\":\"bad-date\",\"tripId\":1}",
            "{\"startDate\":\"2026-02-30\",\"tripId\":1}",
            "{\"startDate\":null,\"tripId\":1}",
            "{\"startDate\":20261120,\"tripId\":1}",
            "{\"startDate\":\"2026-11-20\"}",
            "{\"startDate\":\"2026-11-20\",\"tripId\":null}",
            "{\"startDate\":\"2026-11-20\",\"tripId\":0}",
            "{\"startDate\":\"2026-11-20\",\"tripId\":-1}",
            "{\"startDate\":\"2026-11-20\",\"tripId\":1.5}",
            "{\"startDate\":\"2026-11-20\",\"tripId\":true}",
            "{\"startDate\":\"2026-11-20\",\"tripId\":\"1\"}",
            "{\"startDate\":\"2026-11-20\",\"tripId\":9223372036854775808}"
    })
    void 필수값과_날짜와_JSON_타입과_ID_범위를_검증한다(String json) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> InvitedTripCursor.decode(encoded, objectMapper))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void 직접_생성해도_날짜_누락과_양수가_아닌_ID를_거절한다() {
        assertThatThrownBy(() -> new InvitedTripCursor(null, 1L))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> new InvitedTripCursor(LocalDate.of(2026, 11, 20), 0L))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> new InvitedTripCursor(LocalDate.of(2026, 11, 20), -1L))
                .isInstanceOf(InvalidCursorException.class);
    }
}

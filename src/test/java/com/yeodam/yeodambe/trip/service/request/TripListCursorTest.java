package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.exception.InvalidTripListFilterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Stream;

import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripListCursorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @MethodSource("cursors")
    void 정렬과_즐겨찾기_그룹을_JSON으로_보존한다(TripListCursor cursor) {
        String encoded = cursor.encode(objectMapper);

        assertThat(TripListCursor.decode(encoded, objectMapper)).isEqualTo(cursor);
        assertThat(encoded).doesNotContain("+", "/", "=");
    }

    private static Stream<TripListCursor> cursors() {
        return Arrays.stream(TripSort.values()).flatMap(sort ->
                Stream.of(false, true).flatMap(favorite ->
                        Stream.of(false, true).map(group -> new TripListCursor(
                                sort, favorite, group, LocalDate.of(2026, 9, 22), 17L
                        ))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"true\"", "\"false\"", "\"TRUE\"", "0", "1", "null"})
    void boolean이_아닌_즐겨찾기_값은_거절한다(String value) {
        assertInvalid(json(value, "false", "17"));
        assertInvalid(json("false", value, "17"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "9223372036854775808", "\"17\"", "null"})
    void 양의_long이_아닌_ID는_거절한다(String value) {
        assertInvalid(json("false", "true", value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sort", "favorite", "favoriteGroup", "startDate", "tripId"})
    void 필수필드_누락과_null을_거절한다(String field) {
        var node = objectMapper.createObjectNode()
                .put("sort", "LATEST")
                .put("favorite", false)
                .put("favoriteGroup", true)
                .put("startDate", "2026-09-22")
                .put("tripId", 17L);
        node.remove(field);
        assertInvalid(objectMapper.writeValueAsString(node));
        node.putNull(field);
        assertInvalid(objectMapper.writeValueAsString(node));
    }

    @Test
    void 잘못된_정렬과_날짜를_거절한다() {
        String valid = json("false", "true", "17");
        assertInvalid(valid.replace("LATEST", "NEWEST"));
        assertInvalid(valid.replace("2026-09-22", "not-a-date"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{not-json", "LATEST|false|true|2026-09-22|17"})
    void 손상값과_이전_커서_형식을_거절한다(String json) {
        assertInvalid(json);
    }

    @Test
    void 직접_디코딩의_null과_빈값을_거절한다() {
        assertThatThrownBy(() -> TripListCursor.decode(null, objectMapper))
                .isInstanceOf(InvalidTripListFilterException.class);
        assertThatThrownBy(() -> TripListCursor.decode("", objectMapper))
                .isInstanceOf(InvalidTripListFilterException.class);
    }

    private String json(String favorite, String group, String id) {
        return """
                {"sort":"LATEST","favorite":%s,"favoriteGroup":%s,"startDate":"2026-09-22","tripId":%s}
                """.formatted(favorite, group, id);
    }

    private void assertInvalid(String json) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> TripListCursor.decode(encoded, objectMapper))
                .isInstanceOf(InvalidTripListFilterException.class);
    }
}

package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.exception.InvalidPlaceFolderCursorException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaceFolderCursorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 커서를_URL_safe_Base64로_왕복한다() {
        PlaceFolderCursor cursor = new PlaceFolderCursor(7L, "성산일출봉", 11L);

        String encoded = cursor.encode(objectMapper);
        PlaceFolderCursor decoded = PlaceFolderCursor.decode(encoded, 7L, objectMapper);

        assertThat(decoded).isEqualTo(cursor);
        assertThat(encoded).doesNotContain("+", "/", "=");
    }

    @Test
    void 생략한_커서는_첫_페이지로_처리한다() {
        assertThat(PlaceFolderCursor.decode(null, 7L, objectMapper)).isNull();
    }

    @Test
    void 장소명_정확히_50자는_허용한다() {
        PlaceFolderCursor cursor = new PlaceFolderCursor(7L, "가".repeat(50), 11L);

        assertThat(PlaceFolderCursor.decode(cursor.encode(objectMapper), 7L, objectMapper))
                .isEqualTo(cursor);
    }

    @Test
    void 빈값_손상값_다른여행_잘못된필드는_거부한다() {
        assertInvalid("");
        assertInvalid("   ");
        assertInvalid("not-base64!");
        assertInvalid(encodedJson("{not-json"));
        assertInvalid(encodedJson("{\"placeName\":\"제주\",\"tripPlaceId\":11}"));
        assertInvalid(encodedJson("{\"tripId\":8,\"placeName\":\"제주\",\"tripPlaceId\":11}"));
        assertInvalid(encodedJson("{\"tripId\":7,\"placeName\":\"\",\"tripPlaceId\":11}"));
        assertInvalid(encodedJson("{\"tripId\":7,\"placeName\":\"   \",\"tripPlaceId\":11}"));
        assertInvalid(encodedJson("{\"tripId\":7,\"placeName\":\"" + "가".repeat(51)
                + "\",\"tripPlaceId\":11}"));
        assertInvalid(encodedJson("{\"tripId\":7,\"placeName\":\"제주\",\"tripPlaceId\":0}"));
        assertInvalid(encodedJson("{\"tripId\":7,\"placeName\":\"제주\"}"));
    }

    @Test
    void 예상_여행_ID가_null이면_거부한다() {
        String encoded = new PlaceFolderCursor(7L, "제주", 11L).encode(objectMapper);

        assertThatThrownBy(() -> PlaceFolderCursor.decode(encoded, null, objectMapper))
                .isInstanceOf(InvalidPlaceFolderCursorException.class);
    }

    private void assertInvalid(String cursor) {
        assertThatThrownBy(() -> PlaceFolderCursor.decode(cursor, 7L, objectMapper))
                .isInstanceOf(InvalidPlaceFolderCursorException.class);
    }

    private String encodedJson(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}

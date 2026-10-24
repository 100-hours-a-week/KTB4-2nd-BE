package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentCursorTest {

    private static final String VALID_JSON = """
            {"tripId":7,"tripPlaceId":null,"issue":null,
             "createdAt":"2026-10-09T12:00:00","tripAttachmentId":18}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 커서를_URL_safe_Base64로_왕복한다() {
        AttachmentCursor cursor = new AttachmentCursor(
                LocalDateTime.of(2026, 9, 29, 12, 0),
                18L, 7L, null, null
        );

        String encoded = cursor.encode(objectMapper);
        AttachmentCursor decoded = AttachmentCursor.decode(encoded, 7L, null, null, objectMapper);

        assertThat(decoded).isEqualTo(cursor);
        assertThat(encoded).doesNotContain("+", "/", "=");
    }

    @Test
    void 생략한_커서는_첫_페이지로_처리한다() {
        assertThat(AttachmentCursor.decode(null, 7L, null, null, objectMapper)).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void 첨부_커서의_비양수_ID를_거절한다(long tripAttachmentId) {
        assertThatThrownBy(() -> new AttachmentCursor(
                LocalDateTime.of(2026, 10, 9, 12, 0),
                tripAttachmentId, 7L, null, null
        )).isInstanceOf(InvalidCursorException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void 첨부_커서의_비양수_ID_디코딩을_거절한다(long tripAttachmentId) {
        assertInvalid(encodedJson("""
                {"tripId":7,"tripPlaceId":null,"issue":null,"createdAt":"2026-10-09T12:00:00","tripAttachmentId":%d}
                """.formatted(tripAttachmentId)));
    }

    @Test
    void 잘못된_날짜_문자열의_커서를_거절한다() {
        assertInvalid(encodedJson("""
                {"tripId":7,"tripPlaceId":null,"issue":null,"createdAt":"not-a-date","tripAttachmentId":18}
                """));
    }

    @Test
    void JSON_null이_담긴_커서를_거절한다() {
        assertInvalid("bnVsbA");
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

    @ParameterizedTest
    @ValueSource(strings = {"1.9", "18.0", "\"18\"", "true", "[]", "{}", "9223372036854775808"})
    void 첨부_ID는_long_범위의_JSON_정수만_허용한다(String id) {
        assertInvalid(encodedJson("""
                {"tripId":7,"tripPlaceId":null,"issue":null,"createdAt":"2026-10-09T12:00:00","tripAttachmentId":%s}
                """.formatted(id)));
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL|NULL", "3|NULL", "NULL|BLURRY", "NULL|DUPLICATED", "NULL|UNCLEAR_LOCATION"},
            delimiter = '|', nullValues = "NULL")
    void 같은_소속의_커서는_정상적으로_왕복한다(Long placeId, AttachmentIssue issue) {
        AttachmentCursor cursor = new AttachmentCursor(
                LocalDateTime.of(2026, 10, 9, 12, 0), 18L, 7L, placeId, issue
        );

        assertThat(AttachmentCursor.decode(cursor.encode(objectMapper), 7L, placeId, issue, objectMapper))
                .isEqualTo(cursor);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "8|NULL|NULL|NULL|NULL", "7|3|NULL|4|NULL",
            "7|NULL|BLURRY|NULL|DUPLICATED", "7|3|NULL|NULL|NULL",
            "7|NULL|NULL|3|NULL", "7|NULL|BLURRY|NULL|NULL",
            "7|NULL|NULL|NULL|BLURRY", "7|3|NULL|NULL|BLURRY",
            "7|NULL|BLURRY|3|NULL"
    }, delimiter = '|', nullValues = "NULL")
    void 여행_폴더_사유와_조회_종류가_다르면_거절한다(
            Long tripId, Long placeId, AttachmentIssue issue,
            Long expectedPlaceId, AttachmentIssue expectedIssue
    ) {
        AttachmentCursor cursor = new AttachmentCursor(
                LocalDateTime.of(2026, 10, 9, 12, 0), 18L, tripId, placeId, issue
        );

        assertThatThrownBy(() -> AttachmentCursor.decode(
                cursor.encode(objectMapper), 7L, expectedPlaceId, expectedIssue, objectMapper
        )).isInstanceOf(InvalidCursorException.class);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "tripId|7.9", "tripId|\"7\"", "tripId|9223372036854775808",
            "tripId|0", "tripId|-1", "tripId|null", "tripId|true",
            "tripPlaceId|3.9", "tripPlaceId|\"3\"", "tripPlaceId|9223372036854775808",
            "tripPlaceId|0", "tripPlaceId|-1", "tripPlaceId|true",
            "tripAttachmentId|null", "createdAt|null", "createdAt|123",
            "createdAt|true", "createdAt|[2026,10,9,12,0]", "createdAt|{}",
            "issue|2", "issue|true", "issue|[]", "issue|\"NONE\"", "issue|\"UNKNOWN\""
    }, delimiter = '|')
    void 필드의_타입과_허용값을_검사한다(String field, String value) {
        ObjectNode node = (ObjectNode) objectMapper.readTree(VALID_JSON);
        node.set(field, objectMapper.readTree(value));

        Long expectedPlaceId = field.equals("tripPlaceId") ? 3L : null;
        AttachmentIssue expectedIssue = field.equals("issue") ? AttachmentIssue.BLURRY : null;
        assertThatThrownBy(() -> AttachmentCursor.decode(
                encodedJson(node.toString()), 7L, expectedPlaceId, expectedIssue, objectMapper
        )).isInstanceOf(InvalidCursorException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tripId", "tripPlaceId", "issue", "createdAt", "tripAttachmentId"})
    void 필수_필드가_빠진_커서를_거절한다(String field) {
        ObjectNode node = (ObjectNode) objectMapper.readTree(VALID_JSON);
        node.remove(field);

        assertInvalid(encodedJson(node.toString()));
    }

    @Test
    void 소속_정보가_없는_이전_커서는_거절한다() {
        assertInvalid(encodedJson("""
                {"createdAt":"2026-10-09T12:00:00","tripAttachmentId":18}
                """));
    }

    @Test
    void 장소와_미분류_사유를_동시에_지정할_수_없다() {
        assertThatThrownBy(() -> new AttachmentCursor(
                LocalDateTime.of(2026, 10, 9, 12, 0), 18L, 7L, 3L, AttachmentIssue.BLURRY
        )).isInstanceOf(InvalidCursorException.class);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "1|UNCLEAR_LOCATION", "2|BLURRY", "3|DUPLICATED",
            "' BLURRY'|BLURRY", "'BLURRY '|BLURRY"
    }, delimiter = '|')
    void 숫자_문자열과_공백이_있는_사유는_enum으로_강제_변환하지_않는다(
            String value, AttachmentIssue expectedIssue
    ) {
        ObjectNode node = (ObjectNode) objectMapper.readTree(VALID_JSON);
        node.put("issue", value);

        assertThatThrownBy(() -> AttachmentCursor.decode(
                encodedJson(node.toString()), 7L, null, expectedIssue, objectMapper
        )).isInstanceOf(InvalidCursorException.class);
    }

    private void assertInvalid(String cursor) {
        assertThatThrownBy(() -> AttachmentCursor.decode(cursor, 7L, null, null, objectMapper))
                .isInstanceOf(InvalidCursorException.class);
    }

    private String encodedJson(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}

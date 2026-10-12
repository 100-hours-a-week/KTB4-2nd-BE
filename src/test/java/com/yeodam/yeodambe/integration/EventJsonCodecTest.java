package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.common.event.EventPayload;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.event.EventJsonCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EventJsonCodecTest {

    private static final String SERIALIZATION_FAILURE_MESSAGE = "테스트용 직렬화 실패";

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .build();
    private final EventJsonCodec codec = new EventJsonCodec(mapper);

    @Test
    void 평평한_JSON으로_변환하고_날짜를_포함한_원래_페이로드로_복원한다() {
        var payload = new SamplePayload("예시", OffsetDateTime.parse("2026-10-10T09:00:00+09:00"));

        String json = codec.serialize(payload);

        assertThat(mapper.readTree(json).properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("value", "occurredAt");
        assertThat(codec.deserialize(json, SamplePayload.class)).isEqualTo(payload);
    }

    @Test
    void 잘못된_JSON은_변환_예외를_호출자에게_전달한다() {
        assertThatExceptionOfType(JacksonException.class)
                .isThrownBy(() -> codec.deserialize("{", SamplePayload.class));
    }

    @Test
    void 직렬화_실패를_빈_JSON으로_대체하지_않는다() {
        assertThatExceptionOfType(JacksonException.class)
                .isThrownBy(() -> codec.serialize(new BrokenPayload("예시")))
                .withMessageContaining(SERIALIZATION_FAILURE_MESSAGE);
    }

    @Test
    void 빈_입력과_JSON_null은_페이로드로_허용하지_않는다() {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.serialize(null));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.deserialize(" ", SamplePayload.class));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.deserialize("null", SamplePayload.class));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.deserialize("{}", null));
    }

    private record SamplePayload(String value, OffsetDateTime occurredAt) implements EventPayload {

        @Override
        public EventType eventType() {
            return EventType.TRIP_CREATE_REQUEST;
        }
    }

    private record BrokenPayload(String value) implements EventPayload {

        @Override
        public String value() {
            throw new IllegalStateException(SERIALIZATION_FAILURE_MESSAGE);
        }

        @Override
        public EventType eventType() {
            return EventType.TRIP_CREATE_REQUEST;
        }
    }
}

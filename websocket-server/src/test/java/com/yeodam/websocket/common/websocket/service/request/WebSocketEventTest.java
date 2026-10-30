package com.yeodam.websocket.common.websocket.service.request;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"AI_TASK,USER", "COMMAND_RESULT,USER", "TRIP_EVENT,TRIP"})
    void BE_이벤트_봉투를_읽고_프론트_payload를_유지한다(
            WebSocketEventType type, WebSocketEventTargetType targetType
    ) {
        String json = """
                {
                  "eventId":"11111111-1111-1111-1111-111111111111",
                  "type":"%s",
                  "schemaVersion":1,
                  "occurredAt":"2026-10-28T00:00:00Z",
                  "target":{"type":"%s","id":42},
                  "payload":{
                    "message":"TEST_EVENT",
                    "data":{"status":"COMPLETED","label":"여행","optional":null,"items":[1,2]}
                  }
                }
                """.formatted(type.name(), targetType.name());

        WebSocketEvent event = mapper.readValue(json, WebSocketEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(event.type()).isEqualTo(type);
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-28T00:00:00Z"));
        assertThat(event.target()).isEqualTo(new WebSocketEventTarget(targetType, 42L));
        assertThat(event.payload()).isEqualTo(mapper.readTree(json).path("payload"));
        assertThat(mapper.readTree(mapper.writeValueAsString(event)).path("payload"))
                .isEqualTo(mapper.readTree(json).path("payload"));
    }
}

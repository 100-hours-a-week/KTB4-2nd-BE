package com.yeodam.yeodambe.story.event;

import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class StoryCreateResultEventTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsCompletedKafkaJsonAndReusesExistingResultStructure() {
        var event = mapper.readValue("""
                {"type":"story_result","trip_id":77,"execution_id":"execution-1","status":"COMPLETED",
                 "result":{"story_summary":"여행 이야기","verification":{"passed":false},
                   "blocks":[{"order_number":1,"trip_place_id":11,"trip_attachment_id":21,
                              "day_label":"첫날","detail_summary":"바다","memo":"산책했다."}]},
                 "error":null}
                """, StoryCreateResultEvent.class);

        assertThat(event.type()).isEqualTo("story_result");
        assertThat(event.eventType()).isEqualTo(EventType.STORY_CREATE_RESULT);
        assertThat(event.tripId()).isEqualTo(77L);
        assertThat(event.executionId()).isEqualTo("execution-1");
        assertThat(event.status()).isEqualTo(StoryGenerationAiResponse.Status.COMPLETED);
        assertThat(event.result().storySummary()).isEqualTo("여행 이야기");
        assertThat(event.result().blocks().getFirst().tripAttachmentId()).isEqualTo(21L);

        var response = event.toResponse();
        assertThat(response.tripId()).isEqualTo(event.tripId());
        assertThat(response.executionId()).isEqualTo(event.executionId());
        assertThat(response.result()).isSameAs(event.result());
        assertThat(response.status()).isEqualTo(event.status());
        assertThat(response.progress()).isNull();
        assertThat(response.currentStep()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationAiResponse.Status.class, names = {"FAILED", "CANCELED"})
    void readsTerminalMessageWithoutProgressAndPreservesError(StoryGenerationAiResponse.Status status) {
        String error = status == StoryGenerationAiResponse.Status.FAILED
                ? "{\"code\":\"WORKER_DEAD\",\"message\":\"worker unavailable\",\"detail\":null}" : "null";
        String json = """
                {"type":"story_result","trip_id":77,"execution_id":"execution-1",
                 "status":"%s","result":null,"error":%s}
                """.formatted(status.name(), error);

        var event = mapper.readValue(json, StoryCreateResultEvent.class);
        var response = event.toResponse();

        assertThat(response.status()).isEqualTo(status);
        assertThat(response.result()).isNull();
        assertThat(response.error()).isSameAs(event.error());
        if (status == StoryGenerationAiResponse.Status.FAILED) {
            assertThat(response.error().code()).isEqualTo("WORKER_DEAD");
        } else {
            assertThat(response.error()).isNull();
        }
    }

    @Test
    void internalEventTypeIsNotAnExtraWireField() {
        var event = new StoryCreateResultEvent("story_result", 77L, "execution-1",
                StoryGenerationAiResponse.Status.CANCELED, null, null);

        var json = mapper.readTree(mapper.writeValueAsString(event));

        assertThat(json.path("type").asString()).isEqualTo("story_result");
        assertThat(json.has("eventType")).isFalse();
        assertThat(json.path("trip_id").asLong()).isEqualTo(77L);
        assertThat(json.has("progress")).isFalse();
    }
}

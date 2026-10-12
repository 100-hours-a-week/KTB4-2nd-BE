package com.yeodam.yeodambe.story.client.response;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryGenerationAiResponseTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsProcessingProgressAndEachStep() {
        for (String step : new String[]{"SELECTING", "CAPTIONING", "WRITING", "VERIFYING"}) {
            var response = read("""
                    {
                      "trip_id": 77, "execution_id": "execution-1",
                      "status": "PROCESSING", "progress": {"done": 2, "total": 4},
                      "current_step": "%s", "result": null, "error": null
                    }
                    """.formatted(step));

            assertThat(response.tripId()).isEqualTo(77L);
            assertThat(response.executionId()).isEqualTo("execution-1");
            assertThat(response.status()).isEqualTo(StoryGenerationAiResponse.Status.PROCESSING);
            assertThat(response.progress().done()).isEqualTo(2);
            assertThat(response.progress().total()).isEqualTo(4);
            assertThat(response.currentStep()).isEqualTo(step);
            assertThat(response.result()).isNull();
            assertThat(response.error()).isNull();
        }
    }

    @Test
    void readsThirtyCompletedBlocksAndIgnoresVerification() {
        String blocks = IntStream.rangeClosed(1, 30).mapToObj(i -> """
                {"order_number": %d, "trip_place_id": %d, "trip_attachment_id": %d,
                 "day_label": "첫째 날, 장소", "detail_summary": "사진 요약", "memo": "여행 기록"}
                """.formatted(i, 5001 + (i - 1) / 3, 100 + i))
                .collect(Collectors.joining(","));
        var response = read("""
                {
                  "trip_id": 77, "execution_id": "execution-1", "status": "COMPLETED",
                  "progress": {"done": 4, "total": 4}, "current_step": null,
                  "result": {
                    "story_summary": "바람과 노을로 채운 제주",
                    "blocks": [%s],
                    "verification": {"passed": false, "retry_count": 1}
                  },
                  "error": null
                }
                """.formatted(blocks));

        assertThat(response.status()).isEqualTo(StoryGenerationAiResponse.Status.COMPLETED);
        assertThat(response.result().storySummary()).isEqualTo("바람과 노을로 채운 제주");
        assertThat(response.result().blocks()).hasSize(30);
        var first = response.result().blocks().getFirst();
        assertThat(first.orderNumber()).isEqualTo(1);
        assertThat(first.tripPlaceId()).isEqualTo(5001L);
        assertThat(first.tripAttachmentId()).isEqualTo(101L);
        assertThat(first.dayLabel()).isEqualTo("첫째 날, 장소");
        assertThat(first.detailSummary()).isEqualTo("사진 요약");
        assertThat(first.memo()).isEqualTo("여행 기록");
        assertThat(response.result().blocks().getLast().orderNumber()).isEqualTo(30);
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(response))
                .path("result").has("verification")).isFalse();
    }

    @Test
    void readsFailureAndPreservesErrorDetail() {
        var response = read("""
                {
                  "trip_id": 77, "execution_id": "execution-1", "status": "FAILED",
                  "progress": {"done": 1, "total": 4}, "current_step": null, "result": null,
                  "error": {
                    "code": "DOWNLOAD_FAILED", "message": "사본 다운로드에 실패했습니다.",
                    "detail": {"keys": ["trips/77/analyze/a1.jpg"]}
                  }
                }
                """);

        assertThat(response.status()).isEqualTo(StoryGenerationAiResponse.Status.FAILED);
        assertThat(response.result()).isNull();
        assertThat(response.error().code()).isEqualTo("DOWNLOAD_FAILED");
        assertThat(response.error().message()).isEqualTo("사본 다운로드에 실패했습니다.");
        assertThat(response.error().detail()).isEqualTo(objectMapper.readTree(
                "{\"keys\":[\"trips/77/analyze/a1.jpg\"]}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "CANCELED"})
    void readsWaitingAndCanceledStatusWithoutResult(String status) {
        var response = read("""
                {"trip_id": 77, "execution_id": "execution-1", "status": "%s",
                 "progress": {"done": 0, "total": 4}, "current_step": null,
                 "result": null, "error": null}
                """.formatted(status));

        assertThat(response.status()).isEqualTo(StoryGenerationAiResponse.Status.valueOf(status));
        assertThat(response.result()).isNull();
        assertThat(response.error()).isNull();
    }

    @Test
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> read("""
                {"trip_id": 77, "execution_id": "execution-1", "status": "NOT_A_STATUS"}
                """))
                .hasMessageContaining("NOT_A_STATUS");
    }

    private StoryGenerationAiResponse read(String json) {
        return objectMapper.readValue(json, StoryGenerationAiResponse.class);
    }
}

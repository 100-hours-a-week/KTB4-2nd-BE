package com.yeodam.yeodambe.story.client.request;

import com.yeodam.yeodambe.story.entity.Story;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoryGenerationMessageTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesStoryContractWithSnakeCaseAndKstOffset() {
        var message = message(Story.Mood.EMOTIONAL, 74);

        var json = objectMapper.readTree(objectMapper.writeValueAsString(message));

        assertThat(json).isEqualTo(objectMapper.readTree("""
                {
                  "type": "story",
                  "trip_id": 77,
                  "execution_id": "execution-1",
                  "trip_name": "제주도 가을",
                  "period": {"start_date": "2026-10-12", "end_date": "2026-10-14"},
                  "mood": "EMOTIONAL",
                  "places": [{
                    "trip_place_id": 5001,
                    "place_name": "성산일출봉",
                    "attachments": [{
                      "trip_attachment_id": 101,
                      "analyze_storage_key": "trips/77/analyze/a1.jpg",
                      "taken_at": "2026-10-12T06:14:00+09:00",
                      "evaluation": 74
                    }]
                  }]
                }
                """));
    }

    @Test
    void retainsNullEvaluationInPhotoJson() {
        var json = objectMapper.readTree(objectMapper.writeValueAsString(
                message(Story.Mood.PLAIN, null)));

        var photo = json.path("places").get(0).path("attachments").get(0);
        assertThat(photo.has("evaluation")).isTrue();
        assertThat(photo.path("evaluation").isNull()).isTrue();
    }

    @Test
    void serializesEachMoodAsContractString() {
        for (Story.Mood mood : Story.Mood.values()) {
            var json = objectMapper.readTree(objectMapper.writeValueAsString(message(mood, 74)));
            assertThat(json.path("mood").asString()).isEqualTo(mood.name());
        }
    }

    private StoryGenerationMessage message(Story.Mood mood, Integer evaluation) {
        return new StoryGenerationMessage(
                77L, "execution-1", "제주도 가을",
                new StoryGenerationMessage.Period(
                        LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14)),
                mood,
                List.of(new StoryGenerationMessage.Place(
                        5001L, "성산일출봉",
                        List.of(new StoryGenerationMessage.Attachment(
                                101L, "trips/77/analyze/a1.jpg",
                                OffsetDateTime.parse("2026-10-12T06:14:00+09:00"), evaluation))))
        );
    }
}

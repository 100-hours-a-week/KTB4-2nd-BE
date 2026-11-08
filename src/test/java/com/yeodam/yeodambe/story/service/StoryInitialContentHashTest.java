package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.client.request.StoryGenerationMessage;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class StoryInitialContentHashTest {
    @Test
    void writer의_들여쓰기설정과_무관하게_합의한_해시판본을_유지한다() {
        ObjectMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        StoryGenerationPreparationService service = new StoryGenerationPreparationService(
                null, null, mapper, null, null, null, null, null, null, null, null, null
        );
        StoryGenerationJob job = new StoryGenerationJob("execution-1", 7L, 42L, Story.Mood.PLAIN);
        ReflectionTestUtils.setField(job, "id", 11L);
        job.prepareStory(13L);
        StoryGenerationMessage request = new StoryGenerationMessage(
                7L, "execution-1", "여행", null, Story.Mood.PLAIN,
                List.of(new StoryGenerationMessage.Place(5L, "장소5", List.of()),
                        new StoryGenerationMessage.Place(3L, "장소3", List.of()))
        );
        StoryGenerationAiResponse.Result result = new StoryGenerationAiResponse.Result(
                "요약", List.of(
                        new StoryGenerationAiResponse.Block(2, 5L, 23L, "둘째", "설명", ""),
                        new StoryGenerationAiResponse.Block(1, 3L, 21L, "첫날", "사진", null)
                )
        );

        String hash = ReflectionTestUtils.invokeMethod(service, "initialContentHash", job, request, result,
                Map.of(5L, LocalDate.of(2026, 11, 7), 3L, LocalDate.of(2026, 11, 6)));

        assertThat(hash).isEqualTo("64e5b6540d1940489559cfb570f43d8c7b7a8a4b19d178b07358b1eb94f4e043");
    }
}

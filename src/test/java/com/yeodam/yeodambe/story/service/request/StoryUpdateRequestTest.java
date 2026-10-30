package com.yeodam.yeodambe.story.service.request;

import com.yeodam.yeodambe.story.entity.Story;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

class StoryUpdateRequestTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private static final Validator validator = factory.getValidator();

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void 기존_다섯_분위기와_블록_개수_경계값을_허용한다() {
        for (Story.Mood mood : Story.Mood.values()) {
            for (int size : List.of(1, 30)) {
                assertThat(validator.validate(new StoryUpdateRequest(mood, "요약", blocks(size)))).isEmpty();
            }
        }
    }

    @Test
    void 요약20자와_메모125자는_코드포인트로_검사하고_null메모를_허용한다() {
        for (String memo : java.util.Arrays.asList(null, "", "😀".repeat(125))) {
            var request = new StoryUpdateRequest(Story.Mood.CALM, "😀".repeat(20),
                    List.of(new StoryUpdateRequest.Block(null, 1L, memo, 1)));
            assertThat(validator.validate(request)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\u200B", "\u3164", "\u00A0"})
    void 보이는_내용이_없는_요약을_거절한다(String summary) {
        assertThat(validator.validate(new StoryUpdateRequest(Story.Mood.CALM, summary, blocks(1))))
                .isNotEmpty();
    }

    @Test
    void 요약21자와_메모126자를_거절한다() {
        assertThat(validator.validate(new StoryUpdateRequest(Story.Mood.CALM, "😀".repeat(21), blocks(1))))
                .isNotEmpty();
        assertThat(validator.validate(new StoryUpdateRequest(Story.Mood.CALM, "요약",
                List.of(new StoryUpdateRequest.Block(null, 1L, "😀".repeat(126), 1)))))
                .isNotEmpty();
    }

    @Test
    void 필수값_누락과_빈목록과_31개와_null블록을_거절한다() {
        List<StoryUpdateRequest> requests = List.of(
                new StoryUpdateRequest(null, "요약", blocks(1)),
                new StoryUpdateRequest(Story.Mood.CALM, null, blocks(1)),
                new StoryUpdateRequest(Story.Mood.CALM, "요약", null),
                new StoryUpdateRequest(Story.Mood.CALM, "요약", List.of()),
                new StoryUpdateRequest(Story.Mood.CALM, "요약", blocks(31)),
                new StoryUpdateRequest(Story.Mood.CALM, "요약", Collections.singletonList(null)));
        for (var request : requests) {
            assertThat(validator.validate(request)).isNotEmpty();
        }
    }

    @Test
    void 중첩_블록의_필수값과_양수_조건을_검사한다() {
        List<StoryUpdateRequest.Block> invalid = List.of(
                new StoryUpdateRequest.Block(0L, 1L, null, 1),
                new StoryUpdateRequest.Block(-1L, 1L, null, 1),
                new StoryUpdateRequest.Block(null, null, null, 1),
                new StoryUpdateRequest.Block(null, 0L, null, 1),
                new StoryUpdateRequest.Block(null, -1L, null, 1),
                new StoryUpdateRequest.Block(null, 1L, null, null),
                new StoryUpdateRequest.Block(null, 1L, null, 0),
                new StoryUpdateRequest.Block(null, 1L, null, -1));
        for (var block : invalid) {
            assertThat(validator.validate(new StoryUpdateRequest(Story.Mood.CALM, "요약", List.of(block))))
                    .isNotEmpty();
        }
    }

    @Test
    void 명세의_tone과_선택값_생략을_DTO로_읽는다() {
        var mapper = new ObjectMapper();
        var request = mapper.readValue("""
                {"tone":"CALM","storySummary":"요약",
                 "blocks":[{"tripAttachmentId":10,"orderNumber":1}]}
                """, StoryUpdateRequest.class);
        assertThat(request.tone()).isEqualTo(Story.Mood.CALM);
        assertThat(request.blocks().getFirst().storyBlockId()).isNull();
        assertThat(request.blocks().getFirst().memo()).isNull();
        assertThat(validator.validate(request)).isEmpty();
        assertThatThrownBy(() -> mapper.readValue("""
                {"tone":"UNKNOWN","storySummary":"요약",
                 "blocks":[{"tripAttachmentId":10,"orderNumber":1}]}
                """, StoryUpdateRequest.class)).isInstanceOf(tools.jackson.core.JacksonException.class);
    }

    private List<StoryUpdateRequest.Block> blocks(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new StoryUpdateRequest.Block(null, (long) i, null, i)).toList();
    }
}

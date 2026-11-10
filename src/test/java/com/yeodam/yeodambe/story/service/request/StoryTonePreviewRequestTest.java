package com.yeodam.yeodambe.story.service.request;

import com.yeodam.yeodambe.story.entity.Story;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StoryTonePreviewRequestTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private static final Validator validator = factory.getValidator();

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void 세_분위기와_블록_1개나_30개와_null_초안_텍스트를_허용한다() {
        for (var mood : List.of(Story.Mood.PLAIN, Story.Mood.HUMOROUS, Story.Mood.LITERARY)) {
            for (int size : List.of(1, 30)) {
                assertThat(validator.validate(new StoryTonePreviewRequest(mood, blocks(size)))).isEmpty();
            }
        }
    }

    @Test
    void 폐기되거나_누락된_말투와_잘못된_블록_목록을_거부한다() {
        for (var mood : List.of(Story.Mood.EMOTIONAL, Story.Mood.CALM)) {
            assertThat(validator.validate(new StoryTonePreviewRequest(mood, blocks(1)))).isNotEmpty();
        }
        assertThat(validator.validate(new StoryTonePreviewRequest(null, blocks(1)))).isNotEmpty();
        for (var list : List.of(List.<StoryTonePreviewRequest.Block>of(), blocks(31),
                Collections.<StoryTonePreviewRequest.Block>singletonList(null))) {
            assertThat(validator.validate(new StoryTonePreviewRequest(Story.Mood.PLAIN, list))).isNotEmpty();
        }
        assertThat(validator.validate(new StoryTonePreviewRequest(Story.Mood.PLAIN, null))).isNotEmpty();
    }

    @Test
    void 중첩된_ID와_순서를_검증한다() {
        for (var block : List.of(
                new StoryTonePreviewRequest.Block(0L, 1L, 1, null, null, null),
                new StoryTonePreviewRequest.Block(null, null, 1, null, null, null),
                new StoryTonePreviewRequest.Block(null, -1L, 1, null, null, null),
                new StoryTonePreviewRequest.Block(null, 1L, null, null, null, null),
                new StoryTonePreviewRequest.Block(null, 1L, 0, null, null, null))) {
            assertThat(validator.validate(request(block))).isNotEmpty();
        }
    }

    @Test
    void 보이는_텍스트와_유니코드_코드_포인트_제한을_검증한다() {
        String text = "😀".repeat(40);
        assertThat(validator.validate(request(new StoryTonePreviewRequest.Block(
                1L, 2L, 1, text, text, "😀".repeat(125))))).isEmpty();
        for (String invalid : List.of("", " ", "\u200B", "😀".repeat(41))) {
            assertThat(validator.validate(request(new StoryTonePreviewRequest.Block(
                    null, 2L, 1, invalid, null, null)))).isNotEmpty();
            assertThat(validator.validate(request(new StoryTonePreviewRequest.Block(
                    null, 2L, 1, null, invalid, null)))).isNotEmpty();
        }
        assertThat(validator.validate(request(new StoryTonePreviewRequest.Block(
                null, 2L, 1, null, null, "😀".repeat(126))))).isNotEmpty();
    }

    @Test
    void 메모의_줄바꿈과_탭은_허용하고_깨진_문자와_다른_제어문자는_거절한다() {
        assertThat(validator.validate(request(new StoryTonePreviewRequest.Block(
                null, 1L, 1, null, null, "첫째 줄\n둘째 줄\t😀")))).isEmpty();
        for (String invalid : List.of("본문\000끝", "본문\033끝", "\uD800")) {
            assertThat(validator.validate(request(new StoryTonePreviewRequest.Block(
                    null, 1L, 1, null, null, invalid))))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("blocks[0].memoLengthValid");
        }
    }

    @Test
    void 프론트엔드_JSON에서_미저장_블록과_생략된_텍스트를_읽는다() {
        var request = new ObjectMapper().readValue("""
                {"tone":"HUMOROUS","blocks":[{"tripAttachmentId":135,"orderNumber":1}]}
                """, StoryTonePreviewRequest.class);
        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.blocks().getFirst().storyBlockId()).isNull();
        assertThat(request.blocks().getFirst().memo()).isNull();
    }

    private StoryTonePreviewRequest request(StoryTonePreviewRequest.Block block) {
        return new StoryTonePreviewRequest(Story.Mood.PLAIN, List.of(block));
    }

    private List<StoryTonePreviewRequest.Block> blocks(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new StoryTonePreviewRequest.Block(null, (long) i, i, null, null, null))
                .toList();
    }
}

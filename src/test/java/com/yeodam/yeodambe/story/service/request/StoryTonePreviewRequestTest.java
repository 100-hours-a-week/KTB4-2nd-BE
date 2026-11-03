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
    void acceptsThreeMoodsAndOneOrThirtyBlocksWithNullableDraftText() {
        for (var mood : List.of(Story.Mood.PLAIN, Story.Mood.HUMOROUS, Story.Mood.LITERARY)) {
            for (int size : List.of(1, 30)) {
                assertThat(validator.validate(new StoryTonePreviewRequest(mood, blocks(size)))).isEmpty();
            }
        }
    }

    @Test
    void rejectsRetiredOrMissingToneAndInvalidBlockLists() {
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
    void validatesNestedIdentifiersAndOrder() {
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
    void checksVisibleTextAndUnicodeCodePointLimits() {
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
    void readsUnsavedBlockAndOmittedTextFromFrontendJson() {
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

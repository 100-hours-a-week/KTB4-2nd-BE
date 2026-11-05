package com.yeodam.yeodambe.story.service.request;

import com.yeodam.yeodambe.story.entity.Story;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class StoryGenerationRequestTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private static final Validator validator = factory.getValidator();

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void 현재_분위기와_폴더_1개부터_10개까지_허용한다() {
        for (Story.Mood mood : List.of(Story.Mood.PLAIN, Story.Mood.HUMOROUS, Story.Mood.LITERARY)) {
            assertThat(validator.validate(new StoryGenerationRequest(mood, List.of(101L))))
                    .isEmpty();
            assertThat(validator.validate(new StoryGenerationRequest(
                    mood, LongStream.rangeClosed(1, 10).boxed().toList())))
                    .isEmpty();
        }
    }

    @Test
    void 신규_생성에서_폐기된_분위기를_거부한다() {
        for (Story.Mood mood : List.of(Story.Mood.EMOTIONAL, Story.Mood.CALM)) {
            assertThat(validator.validate(new StoryGenerationRequest(mood, List.of(101L))))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("moodSupported");
        }
    }

    @Test
    void 분위기나_폴더_목록이_없으면_거부한다() {
        assertThat(validator.validate(new StoryGenerationRequest(null, List.of(101L))))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("mood");
        assertThat(validator.validate(new StoryGenerationRequest(Story.Mood.PLAIN, null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("tripPlaceIds");
    }

    @Test
    void 빈_목록과_폴더_11개를_거부한다() {
        assertThat(validator.validate(new StoryGenerationRequest(Story.Mood.PLAIN, List.of())))
                .isNotEmpty();
        assertThat(validator.validate(new StoryGenerationRequest(
                Story.Mood.PLAIN, LongStream.rangeClosed(1, 11).boxed().toList())))
                .isNotEmpty();
    }

    @Test
    void null이나_0이나_음수인_폴더_ID를_거부한다() {
        for (List<Long> ids : List.of(Arrays.asList(101L, null), List.of(0L), List.of(-1L))) {
            assertThat(validator.validate(new StoryGenerationRequest(Story.Mood.PLAIN, ids)))
                    .isNotEmpty();
        }
    }

    @Test
    void 다른_ID는_허용하고_중복된_폴더_ID는_거부한다() {
        assertThat(validator.validate(new StoryGenerationRequest(
                Story.Mood.PLAIN, List.of(101L, 101L, 102L))))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("tripPlaceIdsUnique");
        assertThat(validator.validate(new StoryGenerationRequest(
                Story.Mood.PLAIN, List.of(101L, 102L))))
                .isEmpty();
    }
}

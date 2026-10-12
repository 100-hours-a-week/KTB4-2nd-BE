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
    void acceptsEachCurrentMoodAndOneToTenFolders() {
        for (Story.Mood mood : Story.Mood.values()) {
            assertThat(validator.validate(new StoryGenerationRequest(mood, List.of(101L))))
                    .isEmpty();
            assertThat(validator.validate(new StoryGenerationRequest(
                    mood, LongStream.rangeClosed(1, 10).boxed().toList())))
                    .isEmpty();
        }
    }

    @Test
    void rejectsMissingMoodOrFolderList() {
        assertThat(validator.validate(new StoryGenerationRequest(null, List.of(101L))))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("mood");
        assertThat(validator.validate(new StoryGenerationRequest(Story.Mood.PLAIN, null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("tripPlaceIds");
    }

    @Test
    void rejectsEmptyListAndElevenFolders() {
        assertThat(validator.validate(new StoryGenerationRequest(Story.Mood.PLAIN, List.of())))
                .isNotEmpty();
        assertThat(validator.validate(new StoryGenerationRequest(
                Story.Mood.PLAIN, LongStream.rangeClosed(1, 11).boxed().toList())))
                .isNotEmpty();
    }

    @Test
    void rejectsNullZeroAndNegativeFolderIds() {
        for (List<Long> ids : List.of(Arrays.asList(101L, null), List.of(0L), List.of(-1L))) {
            assertThat(validator.validate(new StoryGenerationRequest(Story.Mood.PLAIN, ids)))
                    .isNotEmpty();
        }
    }

    @Test
    void rejectsDuplicateFolderIdsWithoutRejectingDistinctIds() {
        assertThat(validator.validate(new StoryGenerationRequest(
                Story.Mood.PLAIN, List.of(101L, 101L, 102L))))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("tripPlaceIdsUnique");
        assertThat(validator.validate(new StoryGenerationRequest(
                Story.Mood.PLAIN, List.of(101L, 102L))))
                .isEmpty();
    }
}

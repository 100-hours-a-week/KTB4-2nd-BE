package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.common.exception.InvalidStoryRequestException;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryGenerationRequestValidatorTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final StoryGenerationRequestValidator validator =
            new StoryGenerationRequestValidator(factory.getValidator());

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void acceptsValidRequest() {
        assertThatCode(() -> validator.validate(
                new StoryGenerationRequest(Story.Mood.EMOTIONAL, List.of(101L, 102L))))
                .doesNotThrowAnyException();
    }

    @Test
    void convertsMissingBodyToStoryInputException() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(InvalidStoryRequestException.class);
    }

    @Test
    void convertsConstraintViolationsToStoryInputException() {
        for (StoryGenerationRequest request : List.of(
                new StoryGenerationRequest(null, List.of(101L)),
                new StoryGenerationRequest(Story.Mood.PLAIN, List.of()),
                new StoryGenerationRequest(Story.Mood.PLAIN, List.of(101L, 101L)))) {
            assertThatThrownBy(() -> validator.validate(request))
                    .isInstanceOf(InvalidStoryRequestException.class);
        }
    }
}

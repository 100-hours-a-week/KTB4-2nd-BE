package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.InvalidStoryTonePreviewException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.service.request.StoryTonePreviewRequest;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryTonePreviewRequestValidatorTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final StoryTonePreviewRequestValidator validator =
            new StoryTonePreviewRequestValidator(factory.getValidator());

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void acceptsExistingAndNewBlocksAndContinuousOrdersRegardlessOfArrayOrder() {
        assertThatCode(() -> validator.validate(request(
                block(null, 12L, 2), block(3L, 11L, 1), block(null, 13L, 3))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateBlockOrAttachmentIdentifiers() {
        rejects(request(block(3L, 11L, 1), block(3L, 12L, 2)));
        rejects(request(block(3L, 11L, 1), block(null, 11L, 2)));
    }

    @Test
    void rejectsDuplicateSkippedAndNonpositiveOrders() {
        rejects(request(block(null, 11L, 1), block(null, 12L, 1)));
        rejects(request(block(null, 11L, 1), block(null, 12L, 3)));
        rejects(request(block(null, 11L, 0)));
    }

    @Test
    void convertsMissingBodyAndNestedConstraintViolationsToPreviewInputError() {
        rejects(null);
        rejects(request(block(null, null, 1)));
        rejects(request(block(null, 11L, null)));
        rejects(new StoryTonePreviewRequest(Story.Mood.PLAIN, Arrays.asList((StoryTonePreviewRequest.Block) null)));
        rejects(new StoryTonePreviewRequest(Story.Mood.CALM, List.of(block(null, 11L, 1))));
    }

    private void rejects(StoryTonePreviewRequest request) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(InvalidStoryTonePreviewException.class);
    }

    private StoryTonePreviewRequest request(StoryTonePreviewRequest.Block... blocks) {
        return new StoryTonePreviewRequest(Story.Mood.PLAIN, List.of(blocks));
    }

    private StoryTonePreviewRequest.Block block(Long id, Long attachmentId, Integer order) {
        return new StoryTonePreviewRequest.Block(id, attachmentId, order, null, null, null);
    }
}

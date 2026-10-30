package com.yeodam.yeodambe.story.service.request;

import com.yeodam.yeodambe.common.validation.TextValidation;
import com.yeodam.yeodambe.story.entity.Story;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record StoryUpdateRequest(
        @NotNull
        Story.Mood tone,

        @NotNull
        String storySummary,

        @NotNull
        @Size(min = 1, max = 30)
        List<@NotNull @Valid Block> blocks
) {
    @AssertTrue
    public boolean isStorySummaryValid() {
        return storySummary == null
                || (TextValidation.hasVisibleContent(storySummary)
                && storySummary.codePointCount(
                        0, storySummary.length()
                ) <= 20);
    }

    public record Block(
            @Positive
            Long storyBlockId,

            @NotNull
            @Positive
            Long tripAttachmentId,

            String memo,

            @NotNull
            @Positive
            Integer orderNumber
    ) {
        @AssertTrue
        public boolean isMemoLengthValid() {
            return memo == null
                    || memo.codePointCount(0, memo.length()) <= 125;
        }
    }
}

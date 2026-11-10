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
                || isSingleLineTextValid(storySummary, 20);
    }

    private static boolean isSingleLineTextValid(String value, int maxLength) {
        return TextValidation.hasVisibleContent(value)
                && !TextValidation.containsUnpairedSurrogate(value)
                && value.codePoints().noneMatch(Character::isISOControl)
                && value.codePointCount(0, value.length()) <= maxLength;
    }

    public record Block(
            @Positive
            Long storyBlockId,

            @NotNull
            @Positive
            Long tripAttachmentId,

            @NotNull
            String dayLabel,

            @NotNull
            String detailSummary,

            String memo,

            @NotNull
            @Positive
            Integer orderNumber
    ) {
        @AssertTrue
        public boolean isBlockTextValid() {
            return isSingleLineTextValid(dayLabel, 40)
                    && isSingleLineTextValid(detailSummary, 40);
        }

        @AssertTrue
        public boolean isMemoLengthValid() {
            return memo == null
                    || (!TextValidation.containsUnpairedSurrogate(memo)
                    && !TextValidation.containsUnsupportedMultilineControlCharacter(memo)
                    && memo.codePointCount(0, memo.length()) <= 125);
        }
    }
}

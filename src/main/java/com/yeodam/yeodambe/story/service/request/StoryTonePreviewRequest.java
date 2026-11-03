package com.yeodam.yeodambe.story.service.request;

import com.yeodam.yeodambe.common.validation.TextValidation;
import com.yeodam.yeodambe.story.entity.Story;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record StoryTonePreviewRequest(
        @NotNull
        Story.Mood tone,

        @NotNull
        @Size(min = 1, max = 30)
        List<@NotNull @Valid Block> blocks
) {
    @AssertTrue
    public boolean isToneSupported() {
        return tone == null
                || tone == Story.Mood.PLAIN
                || tone == Story.Mood.HUMOROUS
                || tone == Story.Mood.LITERARY;
    }

    public record Block(
            @Positive
            Long storyBlockId,

            @NotNull
            @Positive
            Long tripAttachmentId,

            @NotNull
            @Positive
            Integer orderNumber,

            String dayLabel,

            String detailSummary,

            String memo
    ) {
        @AssertTrue
        public boolean isBlockTextValid() {
            return isOptionalTextValid(dayLabel, 40)
                    && isOptionalTextValid(detailSummary, 40);
        }

        @AssertTrue
        public boolean isMemoLengthValid() {
            return memo == null
                    || memo.codePointCount(0, memo.length()) <= 125;
        }

        private static boolean isOptionalTextValid(
                String value,
                int maxLength
        ) {
            return value == null
                    || (TextValidation.hasVisibleContent(value)
                    && value.codePointCount(0, value.length()) <= maxLength);
        }
    }
}
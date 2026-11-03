package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryTonePreviewFailedException;
import com.yeodam.yeodambe.common.validation.TextValidation;
import com.yeodam.yeodambe.story.client.request.StoryTonePreviewAiRequest;
import com.yeodam.yeodambe.story.client.response.StoryTonePreviewAiResponse;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class StoryTonePreviewResultValidator {

    public void validate(
            StoryTonePreviewAiRequest request,
            StoryTonePreviewAiResponse response
    ) {
        if (response == null
                || !isRequiredTextValid(response.storySummary(), 20)
                || response.blocks() == null
                || response.blocks().size() != request.blocks().size()) {
            throw new StoryTonePreviewFailedException();
        }

        for (int index = 0; index < request.blocks().size(); index++) {
            StoryTonePreviewAiRequest.Block sent =
                    request.blocks().get(index);
            StoryTonePreviewAiResponse.Block received =
                    response.blocks().get(index);

            if (received == null
                    || !Objects.equals(
                    sent.orderNumber(), received.orderNumber()
            )
                    || !Objects.equals(
                    sent.tripAttachmentId(),
                    received.tripAttachmentId()
            )
                    || !isRequiredTextValid(received.dayLabel(), 40)
                    || !isRequiredTextValid(received.detailSummary(), 40)) {
                throw new StoryTonePreviewFailedException();
            }

            if (sent.dayLabel() != null
                    && !sent.dayLabel().equals(received.dayLabel())) {
                throw new StoryTonePreviewFailedException();
            }

            if ((sent.memo() == null && received.memo() != null)
                    || (received.memo() != null
                    && received.memo().codePointCount(
                    0, received.memo().length()
            ) > 125)) {
                throw new StoryTonePreviewFailedException();
            }
        }
    }

    private boolean isRequiredTextValid(String text, int maxLength) {
        return TextValidation.hasVisibleContent(text)
                && text.codePointCount(0, text.length()) <= maxLength;
    }
}
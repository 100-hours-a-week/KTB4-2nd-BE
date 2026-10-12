package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import org.springframework.stereotype.Component;
import com.yeodam.yeodambe.story.client.request.StoryGenerationMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

@Component
public class StoryGenerationResultValidator {

    public void validate(StoryGenerationAiResponse.Result result) {
        if (result == null
                || !isRequiredTextValid(result.storySummary(), 40)
                || result.blocks() == null
                || result.blocks().isEmpty()
                || result.blocks().size() > 30) {
            throw invalidResponse();
        }

        Set<Integer> orderNumbers = new HashSet<>();
        Set<Long> attachmentIds = new HashSet<>();

        for (var block : result.blocks()) {
            if (block == null
                    || block.orderNumber() == null
                    || block.orderNumber() < 1
                    || !orderNumbers.add(block.orderNumber())
                    || block.tripPlaceId() == null
                    || block.tripPlaceId() <= 0
                    || block.tripAttachmentId() == null
                    || block.tripAttachmentId() <= 0
                    || !attachmentIds.add(block.tripAttachmentId())
                    || !isRequiredTextValid(block.dayLabel(), 40)
                    || !isRequiredTextValid(block.detailSummary(), 40)
                    || (block.memo() != null && block.memo().length() > 125)) {
                throw invalidResponse();
            }
        }
    }

    public void validate(
            StoryGenerationAiResponse.Result result,
            StoryGenerationMessage request
    ) {
        validate(result);

        if (request == null || request.places() == null) {
            throw invalidResponse();
        }

        Map<Long, Long> sentPhotoPlaces = new HashMap<>();

        for (var place : request.places()) {
            for (var attachment : place.attachments()) {
                sentPhotoPlaces.put(
                        attachment.tripAttachmentId(),
                        place.tripPlaceId()
                );
            }
        }

        for (var block : result.blocks()) {
            Long expectedPlaceId =
                    sentPhotoPlaces.get(block.tripAttachmentId());

            if (!block.tripPlaceId().equals(expectedPlaceId)) {
                throw invalidResponse();
            }
        }
    }

    private boolean isRequiredTextValid(String text, int maxLength) {
        return text != null
                && !text.isBlank()
                && text.length() <= maxLength;
    }

    private IllegalArgumentException invalidResponse() {
        return new IllegalArgumentException(
                StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID.message()
        );
    }
}
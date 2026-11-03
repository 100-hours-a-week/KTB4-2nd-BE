package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.InvalidStoryTonePreviewException;
import com.yeodam.yeodambe.story.service.request.StoryTonePreviewRequest;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class StoryTonePreviewRequestValidator {
    private final Validator validator;

    public void validate(StoryTonePreviewRequest request) {
        if (request == null
                || !validator.validate(request).isEmpty()) {
            throw new InvalidStoryTonePreviewException();
        }

        Set<Long> blockIds = new HashSet<>();
        Set<Long> attachmentIds = new HashSet<>();
        Set<Integer> orderNumbers = new HashSet<>();

        for (StoryTonePreviewRequest.Block block : request.blocks()) {
            if (block.storyBlockId() != null
                    && !blockIds.add(block.storyBlockId())) {
                throw new InvalidStoryTonePreviewException();
            }

            if (!attachmentIds.add(block.tripAttachmentId())) {
                throw new InvalidStoryTonePreviewException();
            }

            int orderNumber = block.orderNumber();
            if (orderNumber > request.blocks().size()
                    || !orderNumbers.add(orderNumber)) {
                throw new InvalidStoryTonePreviewException();
            }
        }
    }
}
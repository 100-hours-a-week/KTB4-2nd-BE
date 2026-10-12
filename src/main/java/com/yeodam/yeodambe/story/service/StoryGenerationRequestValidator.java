package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.InvalidStoryRequestException;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoryGenerationRequestValidator {
    private final Validator validator;

    public void validate(StoryGenerationRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new InvalidStoryRequestException();
        }
    }
}
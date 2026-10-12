package com.yeodam.yeodambe.story.exception;

import lombok.Getter;

@Getter
public class StoryGenerationAiRequestException extends RuntimeException {
    private final Integer statusCode;
    private final String errorCode;

    public StoryGenerationAiRequestException(
            StoryGenerationInternalErrorMessage message,
            Integer statusCode,
            String errorCode
    ) {
        super(message.message());
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }
}

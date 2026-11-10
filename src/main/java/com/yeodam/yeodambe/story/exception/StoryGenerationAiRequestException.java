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
        this(message, statusCode, errorCode, null);
    }

    public StoryGenerationAiRequestException(
            StoryGenerationInternalErrorMessage message,
            Integer statusCode,
            String errorCode,
            Throwable cause
    ) {
        super(message.message(), cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }
}

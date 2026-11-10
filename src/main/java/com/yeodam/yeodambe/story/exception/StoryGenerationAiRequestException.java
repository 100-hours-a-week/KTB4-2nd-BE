package com.yeodam.yeodambe.story.exception;

import lombok.Getter;

@Getter
public class StoryGenerationAiRequestException extends RuntimeException {
    private final Integer statusCode;
    private final String errorCode;
    private final boolean transportFailure;

    public StoryGenerationAiRequestException(
            StoryGenerationInternalErrorMessage message,
            Integer statusCode,
            String errorCode
    ) {
        this(message, statusCode, errorCode, false);
    }

    public StoryGenerationAiRequestException(
            StoryGenerationInternalErrorMessage message,
            Integer statusCode,
            String errorCode,
            boolean transportFailure
    ) {
        super(message.message());
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.transportFailure = transportFailure;
    }
}

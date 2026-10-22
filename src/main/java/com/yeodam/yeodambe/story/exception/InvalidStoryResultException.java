package com.yeodam.yeodambe.story.exception;

public class InvalidStoryResultException extends IllegalArgumentException {
    public InvalidStoryResultException(StoryGenerationInternalErrorMessage message) {
        super(message.message());
    }

    public InvalidStoryResultException(StoryGenerationInternalErrorMessage message, Throwable cause) {
        super(message.message(), cause);
    }
}

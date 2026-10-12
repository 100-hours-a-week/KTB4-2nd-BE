package com.yeodam.yeodambe.story.exception;

public class InvalidStoryResultException extends IllegalArgumentException {
    public InvalidStoryResultException(String message) {
        super(message);
    }

    public InvalidStoryResultException(String message, Throwable cause) {
        super(message, cause);
    }
}

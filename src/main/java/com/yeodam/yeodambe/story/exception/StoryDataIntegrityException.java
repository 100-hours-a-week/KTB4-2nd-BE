package com.yeodam.yeodambe.story.exception;
public class StoryDataIntegrityException extends RuntimeException {
    public StoryDataIntegrityException(String reason) {
        super(reason);
    }
}

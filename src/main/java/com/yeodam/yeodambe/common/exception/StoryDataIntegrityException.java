package com.yeodam.yeodambe.common.exception;
public class StoryDataIntegrityException extends RuntimeException {
    public StoryDataIntegrityException(String reason) {
        super(reason);
    }
}

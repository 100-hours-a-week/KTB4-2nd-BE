package com.yeodam.yeodambe.common.exception;

import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;

public class StoryDataIntegrityException extends RuntimeException {
    public StoryDataIntegrityException(StoryGenerationInternalErrorMessage reason) {
        super(reason.message());
    }
}

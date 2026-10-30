package com.yeodam.yeodambe.story.exception;

public class StorySimilarAttachmentAiRequestException extends RuntimeException {
    public StorySimilarAttachmentAiRequestException(
            StorySimilarAttachmentInternalErrorMessage error
    ) {
        super(error.message());
    }
}

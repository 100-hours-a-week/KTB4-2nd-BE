package com.yeodam.yeodambe.common.exception;

import lombok.Getter;

@Getter
public class AttachmentStorageException extends RuntimeException {
    private final String objectKey;

    public AttachmentStorageException(String objectKey, Throwable cause) {
        super(CommonExceptionMessage.ATTACHMENT_STORAGE_FAILED.message(), cause);
        this.objectKey = objectKey;
    }
}

package com.yeodam.yeodambe.common.exception;

import lombok.Getter;

@Getter
public class AttachmentStorageException extends RuntimeException {
    private final String objectKey;

    public AttachmentStorageException(String objectKey, Throwable cause) {
        super("사진 S3 저장에 실패했습니다.", cause);
        this.objectKey = objectKey;
    }
}

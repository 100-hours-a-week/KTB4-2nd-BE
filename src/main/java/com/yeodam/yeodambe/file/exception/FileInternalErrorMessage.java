package com.yeodam.yeodambe.file.exception;

public enum FileInternalErrorMessage {
    INVALID_STORED_FILE_INFORMATION("저장할 파일 정보가 올바르지 않습니다.");

    private final String message;

    FileInternalErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}

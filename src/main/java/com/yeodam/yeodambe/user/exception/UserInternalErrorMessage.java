package com.yeodam.yeodambe.user.exception;

public enum UserInternalErrorMessage {
    ATTACHMENT_STORAGE_SIZE_MISSING("첨부 파일 용량이 기록되지 않았습니다."),
    USER_STATS_NEGATIVE("사용자 통계는 음수일 수 없습니다."),
    SHA_256_UNAVAILABLE("SHA-256을 사용할 수 없습니다.");

    private final String message;

    UserInternalErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}

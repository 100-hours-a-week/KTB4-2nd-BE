package com.yeodam.yeodambe.story.exception;

public enum StorySimilarAttachmentInternalErrorMessage {
    AI_REQUEST_FAILED("AI 유사 사진 조회 요청에 실패했습니다."),
    AI_RESPONSE_INVALID("AI 유사 사진 조회 응답이 올바르지 않습니다.");

    private final String message;

    StorySimilarAttachmentInternalErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}

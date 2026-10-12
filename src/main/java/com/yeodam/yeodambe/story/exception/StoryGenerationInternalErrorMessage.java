package com.yeodam.yeodambe.story.exception;

public enum StoryGenerationInternalErrorMessage {
    AI_STATUS_LOOKUP_FAILED("AI 스토리 생성 상태 조회에 실패했습니다."),
    AI_CANCEL_REQUEST_FAILED("AI 스토리 생성 취소 요청에 실패했습니다."),
    AI_RESPONSE_INVALID("AI 스토리 생성 응답이 올바르지 않습니다."),
    JOB_PROGRESS_INVALID("스토리 생성 진행률은 0부터 100 사이여야 합니다."),
    JOB_STORY_ID_REQUIRED("완료된 스토리의 ID가 필요합니다."),
    JOB_NOT_FOUND("스토리 생성 작업을 찾을 수 없습니다."),
    AI_EXECUTION_MISMATCH("AI 응답의 여행 또는 실행 식별자가 일치하지 않습니다."),
    OUTBOX_NOT_FOUND("스토리 생성 요청 기록을 찾을 수 없습니다.");
    private final String message;

    StoryGenerationInternalErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
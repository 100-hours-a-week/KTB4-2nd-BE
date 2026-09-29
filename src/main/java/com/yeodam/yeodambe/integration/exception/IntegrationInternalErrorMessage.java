package com.yeodam.yeodambe.integration.exception;

public enum IntegrationInternalErrorMessage {
    AI_EC2_INSTANCE_NOT_FOUND("AI EC2 인스턴스를 찾을 수 없습니다."),
    AI_EC2_INVALID_START_STATE("AI EC2를 시작할 수 없는 상태입니다: %s"),
    AI_EC2_START_INTERRUPTED("AI EC2 기동 대기가 중단됐습니다."),
    AI_EC2_START_TIMEOUT("AI EC2 기동 시간이 초과됐습니다."),
    AI_PHOTO_ANALYSIS_REQUEST_FAILED("AI 사진 분석 호출에 실패했습니다."),
    AI_PHOTO_ANALYSIS_STATUS_LOOKUP_FAILED("AI 사진 분석 상태 조회에 실패했습니다."),
    AI_PHOTO_ANALYSIS_CANCEL_FAILED("AI 사진 분석 취소 호출에 실패했습니다."),
    AI_PHOTO_ANALYSIS_CANCEL_RESPONSE_INVALID("AI 사진 분석 취소 응답이 올바르지 않습니다."),
    AI_PHOTO_ANALYSIS_RESULT_INVALID("AI 사진 분석 결과가 올바르지 않습니다."),
    AI_PHOTO_ANALYSIS_STATUS_INVALID("AI 사진 분석 상태가 올바르지 않습니다."),
    AI_SERVER_HEALTH_CHECK_FAILED("AI 서버 상태 확인에 실패했습니다."),
    AI_SERVER_READY_WAIT_INTERRUPTED("AI 서버 준비 대기가 중단됐습니다"),
    AI_SERVER_READY_WAIT_TIMEOUT("AI 서버 준비 시간이 초과됐습니다.");

    private final String message;

    IntegrationInternalErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}

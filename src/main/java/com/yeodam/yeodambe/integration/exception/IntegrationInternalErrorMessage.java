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
    AI_SERVER_READY_WAIT_TIMEOUT("AI 서버 준비 시간이 초과됐습니다."),
    AI_PROGRESS_EXECUTION_MISMATCH("AI 진행률의 실행이 다릅니다."),
    AI_PROGRESS_INVALID("AI 진행률이 잘못됐습니다."),
    AI_PROCESS_STEP_INVALID("AI 처리 단계가 잘못됐습니다."),
    AI_QUERY_HTTP_REQUEST_FAILED("AI 검색 HTTP 요청에 실패했습니다."),
    AI_QUERY_RESPONSE_INVALID("AI 검색 응답 계약 검증에 실패했습니다."),
    AI_QUERY_CANDIDATE_INVALID("AI 검색 후보의 ID 또는 분석용 저장 키가 올바르지 않습니다."),
    AI_QUERY_RESULT_OUTSIDE_CANDIDATES("AI 검색 응답에 후보 외 사진이 포함됐습니다."),
    AI_QUERY_TIMEOUT("AI 검색 처리 시간이 초과됐습니다."),
    AI_QUERY_UNAVAILABLE("AI 검색 서비스를 사용할 수 없습니다.");

    private final String message;

    IntegrationInternalErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}

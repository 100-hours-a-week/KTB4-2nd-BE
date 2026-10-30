package com.yeodam.yeodambe.user.exception;

public enum UserInternalErrorMessage {
    LOGIN_SESSION_USER_ID_MISSING("로그인 세션의 사용자 ID가 필요합니다."),
    LOGIN_SESSION_INPUT_INVALID("로그인 세션 입력이 유효하지 않습니다."),
    LOGIN_SESSION_DUPLICATE("로그인 세션 식별자가 중복되었습니다."),
    LOGIN_SESSION_DATA_INCOMPLETE("로그인 세션 데이터가 불완전합니다."),
    LOGIN_SESSION_DATA_INVALID("로그인 세션 데이터가 유효하지 않습니다."),
    LOGIN_SESSION_INDEX_INVALID("로그인 세션 인덱스가 유효하지 않습니다."),
    AUTH_STORE_TIME_UNAVAILABLE("인증 저장소의 시간을 조회할 수 없습니다."),
    LOGIN_SESSION_UPDATE_CONFLICT("로그인 세션 변경 충돌이 반복되었습니다."),
    TOKEN_ROTATION_RESULT_MISSING("토큰 교체 결과를 확인할 수 없습니다."),
    TOKEN_ROTATION_RESULT_INVALID("토큰 교체 결과가 유효하지 않습니다."),
    AUTH_STORE_UNAVAILABLE("인증 저장소에 연결할 수 없습니다."),
    LOGIN_SESSION_INVALID("로그인 세션이 유효하지 않습니다."),
    CSRF_STORE_INPUT_INVALID("CSRF 저장소 입력이 유효하지 않습니다."),
    OAUTH_STATE_INPUT_INVALID("OAuth state 입력이 유효하지 않습니다."),
    ONBOARDING_TOKEN_INPUT_INVALID("OAuth 가입 토큰 입력이 유효하지 않습니다."),
    OAUTH_TEMPORARY_TOKEN_DUPLICATE("OAuth 임시 토큰 식별자가 중복되었습니다."),
    OAUTH_CLAIM_OWNER_MISSING("OAuth 선점 소유자가 필요합니다."),
    OAUTH_ISSUE_OWNER_MISSING("OAuth 발급 소유자가 필요합니다."),
    OAUTH_STATE_DUPLICATE("OAuth state 식별자가 중복되었습니다."),
    OAUTH_TEMPORARY_DATA_INVALID("OAuth 임시 데이터가 유효하지 않습니다."),
    LOGIN_TICKET_INPUT_INVALID("OAuth 티켓 입력이 유효하지 않습니다."),
    OAUTH_TEMPORARY_STORE_UNAVAILABLE("OAuth 임시 저장소를 사용할 수 없습니다."),
    ACCESS_TOKEN_EXPIRATION_MISSING("액세스 토큰 만료 시각이 없습니다."),
    ATTACHMENT_STORAGE_SIZE_MISSING("첨부 파일 용량이 기록되지 않았습니다."),
    USER_STATS_NEGATIVE("사용자 통계는 음수일 수 없습니다."),
    SHA_256_UNAVAILABLE("SHA-256을 사용할 수 없습니다."),
    USER_STATS_MISSING("사용자 통계가 없습니다.");

    private final String message;

    UserInternalErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}

package com.yeodam.yeodambe.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum CommonExceptionMessage {
    ONBOARDING_TOKEN_REQUIRED("가입 토큰이 필요합니다."),
    CSRF_TOKEN_INVALID("CSRF 토큰이 유효하지 않습니다."),
    PLACE_FOLDER_NOT_FOUND("장소 폴더를 찾을 수 없습니다."),
    LOGIN_TICKET_INVALID("로그인 티켓이 잘못되었습니다."),
    OAUTH_STATE_INVALID_OR_EXPIRED("OAuth state가 유효하지 않거나 만료되었습니다."),
    REFRESH_TOKEN_INVALID_OR_EXPIRED("리프레시 토큰이 유효하지 않거나 만료되었습니다."),
    WITHDRAWAL_FAILED("회원 탈퇴 처리에 실패했습니다."),
    DUPLICATE_EMAIL("이미 가입된 활성 회원의 이메일입니다."),
    KAKAO_AUTHENTICATION_FAILED("카카오 사용자 인증에 실패했습니다."),
    OAUTH_STATE_CREATE_FAILED("OAuth state 생성에 실패했습니다."),
    LOGIN_TICKET_ISSUE_FAILED("로그인 티켓 발급에 실패했습니다."),
    NICKNAME_INVALID("닉네임 형식이 올바르지 않습니다."),
    ONBOARDING_TOKEN_INVALID_OR_EXPIRED("가입 토큰이 없거나 만료되었습니다."),
    CSRF_STORE_UNAVAILABLE("CSRF 저장소를 사용할 수 없습니다."),
    CURSOR_INVALID("커서가 올바르지 않습니다."),
    USER_NOT_FOUND("활성 회원 정보를 찾을 수 없습니다."),
    DUPLICATE_OAUTH_ACCOUNT("이미 연결된 활성 OAuth 계정입니다."),
    OAUTH_PROVIDER_UNAVAILABLE("OAuth 제공자 서비스를 사용할 수 없습니다."),
    ATTACHMENT_STORAGE_FAILED("사진 S3 저장에 실패했습니다."),
    EMAIL_INVALID("이메일 형식이 올바르지 않습니다.");

    private final String message;
}

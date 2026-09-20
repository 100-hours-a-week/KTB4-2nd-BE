package com.yeodam.yeodambe.user.exception;

public class OnboardingTokenInvalidOrExpiredException extends RuntimeException {
    public OnboardingTokenInvalidOrExpiredException() {
        super("가입 토큰이 없거나 만료되었습니다.");
    }
}
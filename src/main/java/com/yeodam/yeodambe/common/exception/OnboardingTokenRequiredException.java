package com.yeodam.yeodambe.common.exception;

public class OnboardingTokenRequiredException extends RuntimeException {
    public OnboardingTokenRequiredException() {
        super("가입 토큰이 필요합니다.");
    }
}
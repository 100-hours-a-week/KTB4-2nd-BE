package com.yeodam.yeodambe.common.exception;

public class OnboardingTokenInvalidOrExpiredException extends RuntimeException {
    public OnboardingTokenInvalidOrExpiredException() {
        super(CommonExceptionMessage.ONBOARDING_TOKEN_INVALID_OR_EXPIRED.message());
    }
}
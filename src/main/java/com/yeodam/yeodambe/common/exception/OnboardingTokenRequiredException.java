package com.yeodam.yeodambe.common.exception;

public class OnboardingTokenRequiredException extends RuntimeException {
    public OnboardingTokenRequiredException() {
        super(CommonExceptionMessage.ONBOARDING_TOKEN_REQUIRED.message());
    }
}
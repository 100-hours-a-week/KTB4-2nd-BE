package com.yeodam.yeodambe.common.exception;

public class KakaoAuthenticationFailedException extends RuntimeException {

    public KakaoAuthenticationFailedException() {
        super(CommonExceptionMessage.KAKAO_AUTHENTICATION_FAILED.message());
    }
}
package com.yeodam.yeodambe.user.exception;

public class KakaoAuthenticationFailedException extends RuntimeException {

    public KakaoAuthenticationFailedException() {
        super("카카오 사용자 인증에 실패했습니다.");
    }
}
package com.yeodam.yeodambe.user.exception;

public class RefreshTokenInvalidOrExpiredException extends RuntimeException {
    public RefreshTokenInvalidOrExpiredException() {
        super("리프레시 토큰이 유효하지 않거나 만료되었습니다.");
    }
}
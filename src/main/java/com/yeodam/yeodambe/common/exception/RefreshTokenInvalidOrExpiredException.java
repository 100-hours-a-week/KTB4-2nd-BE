package com.yeodam.yeodambe.common.exception;

public class RefreshTokenInvalidOrExpiredException extends RuntimeException {
    public RefreshTokenInvalidOrExpiredException() {
        super(CommonExceptionMessage.REFRESH_TOKEN_INVALID_OR_EXPIRED.message());
    }
}
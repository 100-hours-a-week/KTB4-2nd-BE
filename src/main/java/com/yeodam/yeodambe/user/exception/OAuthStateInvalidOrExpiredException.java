package com.yeodam.yeodambe.user.exception;

public class OAuthStateInvalidOrExpiredException extends RuntimeException {

    public OAuthStateInvalidOrExpiredException() {
        super("OAuth state가 유효하지 않거나 만료되었습니다.");
    }
}
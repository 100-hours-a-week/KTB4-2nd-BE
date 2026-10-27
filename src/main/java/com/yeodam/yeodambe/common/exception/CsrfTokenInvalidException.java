package com.yeodam.yeodambe.common.exception;

public class CsrfTokenInvalidException extends RuntimeException {

    public CsrfTokenInvalidException() {
        super("CSRF 토큰이 유효하지 않습니다.");
    }
}
package com.yeodam.yeodambe.common.exception;

public class CsrfTokenInvalidException extends RuntimeException {

    public CsrfTokenInvalidException() {
        super(CommonExceptionMessage.CSRF_TOKEN_INVALID.message());
    }
}
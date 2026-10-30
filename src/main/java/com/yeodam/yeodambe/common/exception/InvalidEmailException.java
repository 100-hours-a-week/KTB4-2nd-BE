package com.yeodam.yeodambe.common.exception;

public class InvalidEmailException extends IllegalArgumentException {

    public InvalidEmailException() {
        super(CommonExceptionMessage.EMAIL_INVALID.message());
    }
}
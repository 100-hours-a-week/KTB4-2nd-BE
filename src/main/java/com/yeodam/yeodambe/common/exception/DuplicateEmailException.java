package com.yeodam.yeodambe.common.exception;

public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException() {
        super(CommonExceptionMessage.DUPLICATE_EMAIL.message());
    }
}
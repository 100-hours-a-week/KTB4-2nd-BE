package com.yeodam.yeodambe.common.exception;

public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException() {
        super(CommonExceptionMessage.CURSOR_INVALID.message());
    }
}
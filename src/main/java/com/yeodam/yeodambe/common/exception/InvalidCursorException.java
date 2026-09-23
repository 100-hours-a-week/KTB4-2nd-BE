package com.yeodam.yeodambe.common.exception;

public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException() {
        super("커서가 올바르지 않습니다.");
    }
}
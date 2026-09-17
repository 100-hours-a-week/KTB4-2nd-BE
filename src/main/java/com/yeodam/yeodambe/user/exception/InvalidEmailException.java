package com.yeodam.yeodambe.user.exception;

public class InvalidEmailException extends IllegalArgumentException {

    public InvalidEmailException() {
        super("이메일 형식이 올바르지 않습니다.");
    }
}
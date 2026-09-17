package com.yeodam.yeodambe.user.exception;

public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException() {
        super("이미 가입된 활성 회원의 이메일입니다.");
    }
}
package com.yeodam.yeodambe.user.exception;

public class DuplicateOAuthAccountException extends RuntimeException {

    public DuplicateOAuthAccountException() {
        super("이미 연결된 활성 OAuth 계정입니다.");
    }
}
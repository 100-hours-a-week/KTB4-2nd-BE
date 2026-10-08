package com.yeodam.yeodambe.common.exception;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException() {
        super("활성 회원 정보를 찾을 수 없습니다.");
    }
}
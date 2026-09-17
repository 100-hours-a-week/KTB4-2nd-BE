package com.yeodam.yeodambe.user.exception;

public class InvalidNicknameException extends IllegalArgumentException {

    public InvalidNicknameException() {

        super("닉네임 형식이 올바르지 않습니다.");
    }
}
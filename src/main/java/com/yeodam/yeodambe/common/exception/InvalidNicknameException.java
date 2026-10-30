package com.yeodam.yeodambe.common.exception;

public class InvalidNicknameException extends IllegalArgumentException {

    public InvalidNicknameException() {

        super(CommonExceptionMessage.NICKNAME_INVALID.message());
    }
}
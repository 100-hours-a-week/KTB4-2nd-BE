package com.yeodam.yeodambe.common.exception;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException() {
        super(CommonExceptionMessage.USER_NOT_FOUND.message());
    }
}
package com.yeodam.yeodambe.user.exception;

public class OAuthStateCreateFailedException extends RuntimeException {

    public OAuthStateCreateFailedException(Throwable cause) {
        super("OAuth state 생성에 실패했습니다.", cause);
    }
}

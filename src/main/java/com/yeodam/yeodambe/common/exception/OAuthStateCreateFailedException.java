package com.yeodam.yeodambe.common.exception;

public class OAuthStateCreateFailedException extends RuntimeException {

    public OAuthStateCreateFailedException(Throwable cause) {
        super(CommonExceptionMessage.OAUTH_STATE_CREATE_FAILED.message(), cause);
    }
}

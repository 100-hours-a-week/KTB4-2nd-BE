package com.yeodam.yeodambe.common.exception;

public class OAuthStateInvalidOrExpiredException extends RuntimeException {

    public OAuthStateInvalidOrExpiredException() {
        super(CommonExceptionMessage.OAUTH_STATE_INVALID_OR_EXPIRED.message());
    }
}
package com.yeodam.yeodambe.common.exception;

public class DuplicateOAuthAccountException extends RuntimeException {

    public DuplicateOAuthAccountException() {
        super(CommonExceptionMessage.DUPLICATE_OAUTH_ACCOUNT.message());
    }
}
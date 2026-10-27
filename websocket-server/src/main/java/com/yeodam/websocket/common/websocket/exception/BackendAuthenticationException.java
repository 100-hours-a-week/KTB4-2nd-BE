package com.yeodam.websocket.common.websocket.exception;

public class BackendAuthenticationException extends RuntimeException {

    private final BackendAuthenticationFailure failure;

    public BackendAuthenticationException(
            BackendAuthenticationFailure failure
    ) {
        super(failure.name());
        this.failure = failure;
    }

    public BackendAuthenticationFailure failure() {
        return failure;
    }
}
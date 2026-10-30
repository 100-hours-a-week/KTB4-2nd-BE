package com.yeodam.websocket.common.websocket.exception;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public class BackendAuthenticationException extends RuntimeException {

    private final BackendAuthenticationFailure failure;

    public BackendAuthenticationException(
            BackendAuthenticationFailure failure
    ) {
        super(failure.name());
        this.failure = failure;
    }
}

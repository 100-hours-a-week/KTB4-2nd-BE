package com.yeodam.yeodambe.common.exception;

public class MapProviderUnavailableException extends RuntimeException {
    public MapProviderUnavailableException(String message) {
        super(message);
    }
    public MapProviderUnavailableException(Throwable cause) {
        super(cause);
    }
}

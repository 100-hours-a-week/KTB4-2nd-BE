package com.yeodam.yeodambe.common.exception;

public class PlaceQueryProviderUnavailableException extends RuntimeException {
    public PlaceQueryProviderUnavailableException(String message) {
        super(message);
    }
    public PlaceQueryProviderUnavailableException(Throwable cause) {
        super(cause);
    }
}

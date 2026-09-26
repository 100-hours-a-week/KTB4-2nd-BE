package com.yeodam.yeodambe.common.response;

public record ApiResponse<T>(
        String message,
        T data
) {
    public ApiResponse(SuccessMessage message, T data) {
        this(message.name(), data);
    }

    public ApiResponse(ErrorMessage message, T data) {
        this(message.name(), data);
    }
}

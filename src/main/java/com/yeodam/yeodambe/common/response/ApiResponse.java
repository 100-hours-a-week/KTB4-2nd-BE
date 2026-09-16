package com.yeodam.yeodambe.common.response;

public record ApiResponse<T>(
        String message,
        T data
) {
}

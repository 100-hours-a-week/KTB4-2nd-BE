package com.yeodam.yeodambe.common.ratelimit;

public class RequestRateLimitUnavailableException extends RuntimeException {
    private final RequestRateLimitGroup group;
    private final String failureType;

    public RequestRateLimitUnavailableException(RequestRateLimitGroup group, String failureType) {
        this.group = group;
        this.failureType = failureType;
    }

    public RequestRateLimitGroup group() {
        return group;
    }

    public String failureType() {
        return failureType;
    }
}

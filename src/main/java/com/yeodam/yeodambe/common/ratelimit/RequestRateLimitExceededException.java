package com.yeodam.yeodambe.common.ratelimit;

public class RequestRateLimitExceededException extends RuntimeException {
    private final RequestRateLimitGroup group;
    private final long retryAfterSeconds;

    public RequestRateLimitExceededException(RequestRateLimitGroup group, long retryAfterSeconds) {
        this.group = group;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public RequestRateLimitGroup group() {
        return group;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

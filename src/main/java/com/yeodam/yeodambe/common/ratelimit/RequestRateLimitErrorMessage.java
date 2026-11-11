package com.yeodam.yeodambe.common.ratelimit;

public enum RequestRateLimitErrorMessage {
    KEY_PREFIX_INVALID("요청 제한 Redis 키 접두사가 올바르지 않습니다."),
    TTL_INVALID("요청 제한 Redis 만료 시간은 0보다 커야 합니다."),
    BUCKET_INVALID("요청 제한 충전 속도와 용량은 0보다 커야 합니다."),
    AUTHENTICATION_REQUIRED("요청 제한에 필요한 인증 정보가 없습니다."),
    CONCURRENCY_LIMIT_INVALID("동시 실행 한도는 양수여야 합니다.");

    private final String message;

    RequestRateLimitErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}

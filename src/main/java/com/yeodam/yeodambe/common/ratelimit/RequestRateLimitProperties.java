package com.yeodam.yeodambe.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("app.request-rate-limit")
public record RequestRateLimitProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("yeodam:v3:local") String keyPrefix,
        @DefaultValue("60s") Duration idleTtl,
        @DefaultValue Bucket tripRead,
        @DefaultValue Bucket tripCreate
) {
    public RequestRateLimitProperties {
        if (keyPrefix == null || !keyPrefix.matches("[A-Za-z0-9:_-]{1,100}")) {
            throw new IllegalArgumentException("요청 제한 Redis 키 접두사가 올바르지 않습니다.");
        }
        if (idleTtl == null || idleTtl.isNegative() || idleTtl.isZero()) {
            throw new IllegalArgumentException("요청 제한 Redis 만료 시간은 0보다 커야 합니다.");
        }
        if (tripRead == null) {
            tripRead = new Bucket(5, 30);
        }
        if (tripCreate == null) {
            tripCreate = new Bucket(1, 5);
        }
    }

    public record Bucket(
            @DefaultValue("1") int refillPerSecond,
            @DefaultValue("1") int capacity
    ) {
        public Bucket {
            if (refillPerSecond < 1 || capacity < 1) {
                throw new IllegalArgumentException("요청 제한 충전 속도와 용량은 0보다 커야 합니다.");
            }
        }
    }
}

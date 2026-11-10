package com.yeodam.yeodambe.common.ratelimit;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "app.request-rate-limit", name = "enabled", havingValue = "true")
public class RedisTokenBucketRateLimiter {

    private static final RedisScript<Long> SCRIPT = RedisScript.of(
            new ClassPathResource("redis/request-rate-limit.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final RequestRateLimitProperties properties;

    public RedisTokenBucketRateLimiter(
            StringRedisTemplate redis,
            RequestRateLimitProperties properties
    ) {
        this.redis = redis;
        this.properties = properties;
    }

    public long tryAcquire(RequestRateLimitGroup group, long userId) {
        RequestRateLimitProperties.Bucket bucket = bucketFor(group);
        String key = "%s:%s:%d".formatted(properties.keyPrefix(), group.key(), userId);

        Long result;
        try {
            result = redis.execute(
                    SCRIPT,
                    List.of(key),
                    Integer.toString(bucket.capacity()),
                    Integer.toString(bucket.refillPerSecond()),
                    Long.toString(properties.idleTtl().toMillis())
            );
        } catch (DataAccessException exception) {
            throw new RequestRateLimitUnavailableException(group, exception.getClass().getSimpleName());
        }

        if (result == null) {
            throw new RequestRateLimitUnavailableException(group, "EMPTY_SCRIPT_RESULT");
        }
        if (result == 1L) {
            return 0L;
        }
        if (result < 0L) {
            return -result;
        }
        throw new RequestRateLimitUnavailableException(group, "INVALID_SCRIPT_RESULT");
    }

    private RequestRateLimitProperties.Bucket bucketFor(RequestRateLimitGroup group) {
        return switch (group) {
            case TRIP_READ -> properties.tripRead();
            case TRIP_CREATE -> properties.tripCreate();
        };
    }
}

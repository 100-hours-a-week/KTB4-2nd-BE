package com.yeodam.yeodambe.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisTokenBucketRateLimiterTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void Redis_연결이_실패하면_요청을_허용하지_않고_저장소_장애로_전달한다() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("Redis is unavailable"));
        RequestRateLimitProperties properties = new RequestRateLimitProperties(
                true,
                "yeodam:test",
                Duration.ofSeconds(60),
                new RequestRateLimitProperties.Bucket(5, 30),
                new RequestRateLimitProperties.Bucket(1, 5)
        );
        RedisTokenBucketRateLimiter rateLimiter = new RedisTokenBucketRateLimiter(redis, properties);

        assertThatThrownBy(() -> rateLimiter.tryAcquire(RequestRateLimitGroup.TRIP_READ, 42L))
                .isInstanceOfSatisfying(RequestRateLimitUnavailableException.class,
                        exception -> {
                            assertThat(exception.group()).isEqualTo(RequestRateLimitGroup.TRIP_READ);
                            assertThat(exception.failureType()).isEqualTo("RedisConnectionFailureException");
                        });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void Lua가_결과를_반환하지_않으면_요청을_허용하지_않는다() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        RequestRateLimitProperties properties = new RequestRateLimitProperties(
                true,
                "yeodam:test",
                Duration.ofSeconds(60),
                new RequestRateLimitProperties.Bucket(5, 30),
                new RequestRateLimitProperties.Bucket(1, 5)
        );
        RedisTokenBucketRateLimiter rateLimiter = new RedisTokenBucketRateLimiter(redis, properties);

        assertThatThrownBy(() -> rateLimiter.tryAcquire(RequestRateLimitGroup.TRIP_READ, 42L))
                .isInstanceOfSatisfying(RequestRateLimitUnavailableException.class,
                        exception -> assertThat(exception.failureType()).isEqualTo("EMPTY_SCRIPT_RESULT"));
    }
}

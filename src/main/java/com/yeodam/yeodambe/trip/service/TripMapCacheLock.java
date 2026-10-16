package com.yeodam.yeodambe.trip.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
public class TripMapCacheLock {

    private static final String KEY_PREFIX = "lock:trip-map:user:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>("""
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                end
                return 0
                """, Long.class);
    private final StringRedisTemplate redisTemplate;

    private String key(Long userId, String version) {
        return KEY_PREFIX + userId + ":" + version;
    }

    public boolean tryAcquire(
            Long userId,
            String version,
            String owner
    ) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(
                        key(userId, version),
                        owner,
                        LOCK_TTL
                )
        );
    }

    public void release(
            Long userId,
            String version,
            String owner
    ) {
        try {
            redisTemplate.execute(
                    RELEASE_SCRIPT,
                    List.of(key(userId, version)),
                    owner
            );
        } catch (DataAccessException exception) {
            log.warn(
                    "메인 지도 캐시 잠금 해제에 실패했습니다. userId={}",
                    userId,
                    exception
            );
        }
    }
}
package com.yeodam.yeodambe.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import com.yeodam.yeodambe.trip.service.response.TripMapCacheData;

import java.util.Optional;
import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
public class TripMapCacheStore {

    private static final String KEY_PREFIX = "cache:trip-map:user:";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    public Optional<TripMapCacheData> find(Long userId) {
        try {
            String json = redisTemplate.opsForValue().get(key(userId));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(
                    objectMapper.readValue(json, TripMapCacheData.class)
            );
        } catch (DataAccessException | JacksonException exception) {
            log.warn("메인 지도 캐시 조회에 실패했습니다. userId={}",
                    userId, exception);
            return Optional.empty();
        }
    }

    public void save(Long userId, TripMapCacheData data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(key(userId), json, TTL);
        } catch (DataAccessException | JacksonException exception) {
            log.warn("메인 지도 캐시 저장에 실패했습니다. userId={}",
                    userId, exception);
        }
    }

    public void evict(Long userId) {
        redisTemplate.delete(key(userId));
    }
}

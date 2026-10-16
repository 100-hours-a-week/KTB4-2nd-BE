package com.yeodam.yeodambe.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import com.yeodam.yeodambe.trip.service.response.TripMapCacheData;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class TripMapCacheStore {

    private static final String KEY_PREFIX = "cache:trip-map:user:";
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String VERSION_KEY_PREFIX = "cache:trip-map:version:user:";
    private static final Duration VERSION_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private String key(Long userId, String version) {
        return KEY_PREFIX + userId + ":" + version;
    }

    public Optional<String> version(Long userId) {
        String versionKey = VERSION_KEY_PREFIX + userId;
        try {
            String existing = redisTemplate.opsForValue().get(versionKey);
            if (existing != null) {
                return Optional.of(existing);
            }
            String created = UUID.randomUUID().toString();
            Boolean saved = redisTemplate.opsForValue()
                    .setIfAbsent(versionKey, created, VERSION_TTL);
            if (Boolean.TRUE.equals(saved)) {
                return Optional.of(created);
            }
            return Optional.ofNullable(redisTemplate.opsForValue().get(versionKey));
        } catch (DataAccessException exception) {
            log.warn("메인 지도 캐시 버전 조회에 실패했습니다. userId={}",
                    userId, exception);
            return Optional.empty();
        }
    }

    public Optional<TripMapCacheData> find(Long userId, String version) {
        try {
            String json = redisTemplate.opsForValue()
                    .get(key(userId, version));
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

    public void save(Long userId, String version, TripMapCacheData data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue()
                    .set(key(userId, version), json, TTL);
        } catch (DataAccessException | JacksonException exception) {
            log.warn("메인 지도 캐시 저장에 실패했습니다. userId={}",
                    userId, exception);
        }
    }

    public void evict(Long userId) {
        try {
            redisTemplate.opsForValue().set(
                    VERSION_KEY_PREFIX + userId,
                    UUID.randomUUID().toString(),
                    VERSION_TTL
            );
        } catch (DataAccessException exception) {
            log.warn("메인 지도 캐시 무효화에 실패했습니다. userId={}",
                    userId, exception);
        }
    }

    public void evictAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            evict(userId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        evict(userId);
                    }
                }
        );
    }
}

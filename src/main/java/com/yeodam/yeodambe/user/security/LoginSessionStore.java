package com.yeodam.yeodambe.user.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LoginSessionStore {

    private static final String KEY_PREFIX = "auth:session:";
    private static final String REFRESH_INDEX_PREFIX = "auth:refresh:";
    private static final Duration SESSION_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void save(String sid, Long userId, String refreshTokenHash) {
        LoginSession session = new LoginSession(userId, refreshTokenHash);

        try {
            String json = objectMapper.writeValueAsString(session);
            redisTemplate.opsForValue().set(
                    key(sid),
                    json,
                    SESSION_TTL
            );
            redisTemplate.opsForValue().set(
                    refreshIndexKey(refreshTokenHash),
                    sid,
                    SESSION_TTL
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("로그인 세션 변환에 실패했습니다.", e);
        }
    }

    public Optional<LoginSession> findBySid(String sid) {
        if (sid == null || sid.isBlank()) {
            return Optional.empty();
        }

        String json = redisTemplate.opsForValue().get(key(sid));
        if (json == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(
                    objectMapper.readValue(json, LoginSession.class)
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("로그인 세션 복원에 실패했습니다.", e);
        }
    }

    public Optional<String> findSidByRefreshTokenHash(
            String refreshTokenHash
    ) {
        if (refreshTokenHash == null || refreshTokenHash.isBlank()) {
            return Optional.empty();
        }

        return Optional.ofNullable(
                redisTemplate.opsForValue().get(
                        refreshIndexKey(refreshTokenHash)
                )
        );
    }

    private String key(String sid) {
        return KEY_PREFIX + sid;
    }

    private String refreshIndexKey(String refreshTokenHash) {
        return REFRESH_INDEX_PREFIX + refreshTokenHash;
    }
}
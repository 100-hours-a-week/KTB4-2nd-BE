package com.yeodam.yeodambe.user.security.session;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LoginSessionStore {

    private static final String KEY_PREFIX = "auth:session:";
    private static final String REFRESH_INDEX_PREFIX = "auth:refresh:";
    private static final Duration SESSION_TTL = Duration.ofDays(7);

    private static final DefaultRedisScript<Long> ROTATE_SCRIPT =
            new DefaultRedisScript<>();

    static {
        ROTATE_SCRIPT.setLocation(
                new ClassPathResource("rotate-refresh-token.lua")
        );
        ROTATE_SCRIPT.setResultType(Long.class);
    }

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

    public Optional<Long> rotate(String oldHash, String newHash) {
        Optional<String> sidResult = findSidByRefreshTokenHash(oldHash);
        if (sidResult.isEmpty()) {
            return Optional.empty();
        }

        String sid = sidResult.get();
        Optional<LoginSession> sessionResult = findBySid(sid);
        if (sessionResult.isEmpty()) {
            return Optional.empty();
        }

        LoginSession session = sessionResult.get();

        try {
            String newJson = objectMapper.writeValueAsString(
                    new LoginSession(session.userId(), newHash)
            );

            Long result = redisTemplate.execute(
                    ROTATE_SCRIPT,
                    List.of(
                            refreshIndexKey(oldHash),
                            key(sid),
                            refreshIndexKey(newHash)
                    ),
                    sid,
                    oldHash,
                    newJson,
                    Long.toString(SESSION_TTL.toSeconds())
            );

            return Long.valueOf(1L).equals(result)
                    ? Optional.of(session.userId())
                    : Optional.empty();
        } catch (JacksonException e) {
            throw new IllegalStateException("로그인 세션 변환에 실패했습니다.", e);
        }
    }

    private String key(String sid) {
        return KEY_PREFIX + sid;
    }

    private String refreshIndexKey(String refreshTokenHash) {
        return REFRESH_INDEX_PREFIX + refreshTokenHash;
    }
}
package com.yeodam.yeodambe.user.security;

import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ProfileTokenStore {

    private static final String KEY_PREFIX = "auth:profile-token:";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void save(String token, KakaoUserIdentity identity) {
        try {
            String json = objectMapper.writeValueAsString(identity);
            redisTemplate.opsForValue().set(
                    key(token),
                    json,
                    TOKEN_TTL
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("가입 토큰 데이터 변환에 실패했습니다.", e);
        }
    }

    public Optional<KakaoUserIdentity> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String json = redisTemplate.opsForValue().get(key(token));
        if (json == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(
                    objectMapper.readValue(json, KakaoUserIdentity.class)
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("가입 토큰 데이터 복원에 실패했습니다.", e);
        }
    }

    public void delete(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        redisTemplate.delete(key(token));
    }

    private String key(String token) {
        return KEY_PREFIX + token;
    }
}
package com.yeodam.yeodambe.user.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class CsrfTokenStore {

    private static final String KEY_PREFIX = "auth:csrf:";
    private static final Duration TOKEN_TTL =
            Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    public void save(
            String browserContext,
            String token
    ) {
        redisTemplate.opsForValue().set(
                key(browserContext),
                token,
                TOKEN_TTL
        );
    }

    public boolean matches(
            String browserContext,
            String token
    ) {
        if (browserContext == null
                || browserContext.isBlank()
                || token == null
                || token.isBlank()) {
            return false;
        }

        String storedToken = redisTemplate
                .opsForValue()
                .get(key(browserContext));

        return token.equals(storedToken);
    }

    private String key(String browserContext) {
        return KEY_PREFIX + browserContext;
    }
}
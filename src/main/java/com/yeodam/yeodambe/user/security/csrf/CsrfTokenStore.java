package com.yeodam.yeodambe.user.security.csrf;

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

    public String find(String browserContext) {
        if (browserContext == null || browserContext.isBlank()) {
            return null;
        }

        return redisTemplate.opsForValue().get(key(browserContext));
    }

    public void delete(String browserContext) {
        if (browserContext == null || browserContext.isBlank()) {
            return;
        }

        redisTemplate.delete(key(browserContext));
    }

    private String key(String browserContext) {
        return KEY_PREFIX + browserContext;
    }
}
package com.yeodam.yeodambe.user.security.csrf;

import com.yeodam.yeodambe.user.exception.CsrfStoreUnavailableException;
import com.yeodam.yeodambe.user.security.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

@Component
public class CsrfTokenStore {
    private static final Duration TOKEN_TTL = Duration.ofDays(7);
    private final StringRedisTemplate redis;
    private final TokenHasher hasher;
    private final String prefix;

    public CsrfTokenStore(StringRedisTemplate redis, TokenHasher hasher,
                          @Value("${auth.session.key-prefix}") String prefix) {
        this.redis = redis;
        this.hasher = hasher;
        this.prefix = prefix;
    }

    public String findOrCreate(String browserContext, Supplier<String> generator) {
        requireValue(browserContext);
        try {
            String key = key(browserContext);
            for (int attempt = 0; attempt < 16; attempt++) {
                String existing = read(key);
                if (existing != null) {
                    return existing;
                }
                String token = generator.get();
                requireValue(token);
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, TOKEN_TTL))) {
                    return token;
                }
            }
            throw new CsrfStoreUnavailableException();
        } catch (DataAccessException exception) {
            throw new CsrfStoreUnavailableException();
        }
    }

    public void save(String browserContext, String token) {
        requireValue(browserContext);
        requireValue(token);
        try {
            redis.opsForValue().set(key(browserContext), token, TOKEN_TTL);
        } catch (DataAccessException exception) {
            throw new CsrfStoreUnavailableException();
        }
    }

    public String find(String browserContext) {
        if (isBlank(browserContext)) {
            return null;
        }
        try {
            return read(key(browserContext));
        } catch (DataAccessException exception) {
            throw new CsrfStoreUnavailableException();
        }
    }

    public boolean matches(String browserContext, String token) {
        return !isBlank(browserContext) && !isBlank(token) && token.equals(find(browserContext));
    }

    public void delete(String browserContext) {
        if (isBlank(browserContext)) {
            return;
        }
        try {
            redis.delete(key(browserContext));
        } catch (DataAccessException exception) {
            throw new CsrfStoreUnavailableException();
        }
    }

    private String read(String key) {
        String value = redis.opsForValue().get(key);
        if (value != null && value.isBlank()) {
            throw new CsrfStoreUnavailableException();
        }
        return value;
    }

    private String key(String browserContext) {
        return prefix + "csrf:" + hasher.hash(browserContext);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireValue(String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("CSRF 저장소 입력이 유효하지 않습니다.");
        }
    }
}

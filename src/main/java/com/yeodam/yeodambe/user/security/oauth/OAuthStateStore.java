package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.user.security.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OAuthStateStore {
    private final RedisOAuthTokenOperations operations;
    private final TokenHasher hasher;
    private final String prefix;

    public OAuthStateStore(RedisOAuthTokenOperations operations, TokenHasher hasher,
                           @Value("${auth.session.key-prefix}") String prefix) {
        this.operations = operations;
        this.hasher = hasher;
        this.prefix = prefix;
    }

    public void save(String state, String browserContext) {
        if (isBlank(state) || isBlank(browserContext)) {
            throw new IllegalArgumentException("OAuth state 입력이 유효하지 않습니다.");
        }
        operations.saveState(key(state), hasher.hash(browserContext), Duration.ofMinutes(5));
    }

    public boolean consume(String state, String browserContext) {
        return !isBlank(state) && !isBlank(browserContext)
                && operations.consumeState(key(state), hasher.hash(browserContext));
    }

    private String key(String state) {
        return prefix + "oauth-state:" + hasher.hash(state);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

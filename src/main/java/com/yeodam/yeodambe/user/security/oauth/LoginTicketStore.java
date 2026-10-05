package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Component
public class LoginTicketStore {
    private final RedisOAuthTokenOperations operations;
    private final TokenHasher hasher;
    private final String prefix;

    public LoginTicketStore(RedisOAuthTokenOperations operations, TokenHasher hasher,
                              @Value("${auth.session.key-prefix}") String prefix) {
        this.operations = operations;
        this.hasher = hasher;
        this.prefix = prefix;
    }

    public void save(String token, KakaoUserIdentity identity, String browserContext) {
        if (isBlank(token) || isBlank(browserContext)) {
            throw new IllegalArgumentException("OAuth 티켓 입력이 유효하지 않습니다.");
        }
        operations.save(key(token), identity, hasher.hash(browserContext), Duration.ofMinutes(1), UUID.randomUUID().toString());
    }

    public Optional<KakaoUserIdentity> consume(String token, String browserContext) {
        String owner = UUID.randomUUID().toString();
        return claim(token, browserContext, owner).map(claim -> {
            complete(token, owner);
            return claim.identity();
        });
    }

    public Optional<OAuthTokenClaim> claim(String token, String browserContext, String owner) {
        return isBlank(token) || isBlank(browserContext) ? Optional.empty() : operations.claim(key(token), hasher.hash(browserContext), owner);
    }

    public void complete(String token, String owner) {
        if (!isBlank(token)) {
            operations.complete(key(token), owner);
        }
    }

    public void release(String token, String owner) {
        if (!isBlank(token)) {
            operations.release(key(token), owner);
        }
    }

    private String key(String token) {
        return prefix + "login-ticket:" + hasher.hash(token);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

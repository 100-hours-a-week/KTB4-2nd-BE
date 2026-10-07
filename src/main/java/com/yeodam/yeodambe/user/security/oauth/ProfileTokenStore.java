package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Component
public class ProfileTokenStore {
    private final RedisOAuthTokenOperations operations;
    private final TokenHasher hasher;
    private final String prefix;

    public ProfileTokenStore(RedisOAuthTokenOperations operations, TokenHasher hasher,
                              @Value("${auth.session.key-prefix}") String prefix) {
        this.operations = operations;
        this.hasher = hasher;
        this.prefix = prefix;
    }

    public void save(String token, KakaoUserIdentity identity) {
        save(token, identity, UUID.randomUUID().toString());
    }

    public void save(String token, KakaoUserIdentity identity, String issuanceOwner) {
        if (isBlank(token)) {
            throw new IllegalArgumentException("OAuth 가입 토큰 입력이 유효하지 않습니다.");
        }
        operations.save(key(token), identity, null, Duration.ofMinutes(10), issuanceOwner);
    }

    public Optional<KakaoUserIdentity> find(String token) {
        return isBlank(token) ? Optional.empty() : operations.find(key(token));
    }

    public void delete(String token) {
        if (!isBlank(token)) {
            operations.delete(key(token));
        }
    }

    public void deleteIfIssuedBy(String token, String issuanceOwner) {
        if (!isBlank(token)) {
            operations.deleteIfIssuedBy(key(token), issuanceOwner);
        }
    }

    public Optional<OAuthTokenClaim> claim(String token, String owner) {
        return isBlank(token) ? Optional.empty() : operations.claim(key(token), null, owner);
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
        return prefix + "profile-token:" + hasher.hash(token);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.SetCondition;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

@Component
@RequiredArgsConstructor
class RedisOAuthTokenOperations {
    private static final int MAX_TRANSACTION_ATTEMPTS = 16;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    void save(String key, KakaoUserIdentity identity, String browserHash, Duration ttl, String issuanceOwner) {
        OAuthTemporaryToken token = new OAuthTemporaryToken(identity, browserHash,
                now(redis) + ttl.toMillis(), null, issuanceOwner);
        String json = encode(token);
        try {
            if (!Boolean.TRUE.equals(set(redis, key, json, token.expiresAt(), SetCondition.ifAbsent()))) {
                throw new DataIntegrityViolationException("OAuth 임시 토큰 식별자가 중복되었습니다.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    Optional<KakaoUserIdentity> find(String key) {
        try {
            return Optional.ofNullable(read(redis, key))
                    .filter(token -> token.claimOwner() == null)
                    .map(OAuthTemporaryToken::identity);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    Optional<OAuthTokenClaim> claim(String key, String browserHash, String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("OAuth 선점 소유자가 필요합니다.");
        }
        return execute(operations -> {
            operations.watch(key);
            OAuthTemporaryToken token = read(operations, key);
            if (token != null && browserHash != null && token.browserContextHash() == null) {
                throw unavailable();
            }
            if (token == null || token.claimOwner() != null
                    || !Objects.equals(browserHash, token.browserContextHash())) {
                return Optional.empty();
            }
            OAuthTemporaryToken claimed = token.withClaimOwner(owner);
            return commit(operations, () -> set(operations, key, encode(claimed),
                    claimed.expiresAt(), SetCondition.upsert()))
                    ? Optional.of(new OAuthTokenClaim(token.identity(), owner)) : null;
        });
    }

    void complete(String key, String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("OAuth 선점 소유자가 필요합니다.");
        }
        deleteIf(key, token -> owner.equals(token.claimOwner()));
    }

    void release(String key, String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("OAuth 선점 소유자가 필요합니다.");
        }
        execute(operations -> {
            operations.watch(key);
            OAuthTemporaryToken token = read(operations, key);
            if (token == null || !owner.equals(token.claimOwner())) {
                return true;
            }
            return commit(operations, () -> set(operations, key,
                    encode(token.withClaimOwner(null)), token.expiresAt(), SetCondition.upsert())) ? true : null;
        });
    }

    void deleteIfIssuedBy(String key, String issuanceOwner) {
        if (issuanceOwner == null || issuanceOwner.isBlank()) {
            throw new IllegalArgumentException("OAuth 발급 소유자가 필요합니다.");
        }
        deleteIf(key, token -> issuanceOwner.equals(token.issuanceOwner()));
    }

    void delete(String key) {
        try {
            redis.delete(key);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private void deleteIf(String key, Predicate<OAuthTemporaryToken> matches) {
        execute(operations -> {
            operations.watch(key);
            OAuthTemporaryToken token = read(operations, key);
            if (token == null || !matches.test(token)) {
                return true;
            }
            return commit(operations, () -> operations.delete(key)) ? true : null;
        });
    }

    boolean consumeState(String key, String browserHash) {
        return execute(operations -> {
            operations.watch(key);
            String actual = operations.opsForValue().get(key);
            if (actual == null) {
                return false;
            }
            if (!actual.matches("[0-9a-f]{64}")) {
                throw unavailable();
            }
            if (!browserHash.equals(actual)) {
                return false;
            }
            return commit(operations, () -> operations.delete(key)) ? true : null;
        });
    }

    void saveState(String key, String browserHash, Duration ttl) {
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, browserHash, ttl))) {
                throw new DataIntegrityViolationException("OAuth state 식별자가 중복되었습니다.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private OAuthTemporaryToken read(RedisOperations<String, String> operations, String key) {
        String json = operations.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            OAuthTemporaryToken token = objectMapper.readValue(json, OAuthTemporaryToken.class);
            if (token == null || Long.valueOf(-1L).equals(operations.getExpire(key))) {
                throw unavailable();
            }
            return token.expiresAt() > now(operations) ? token : null;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    private String encode(OAuthTemporaryToken token) {
        try {
            return objectMapper.writeValueAsString(token);
        } catch (JacksonException exception) {
            throw unavailable();
        }
    }

    private long now(RedisOperations<String, String> operations) {
        try {
            Long time = operations.execute((RedisCallback<Long>) connection ->
                    connection.serverCommands().time(TimeUnit.MILLISECONDS));
            if (time == null) {
                throw unavailable();
            }
            return time;
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private Boolean set(RedisOperations<String, String> operations, String key,
                        String value, long expiresAt, SetCondition condition) {
        return operations.execute((RedisCallback<Boolean>) connection -> connection.stringCommands().set(
                key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8),
                condition, Expiration.unixTimestamp(expiresAt, TimeUnit.MILLISECONDS)));
    }

    private boolean commit(RedisOperations<String, String> operations, Runnable writes) {
        boolean active = false;
        try {
            operations.multi();
            active = true;
            writes.run();
            List<Object> results = operations.exec();
            active = false;
            if (results == null || results.isEmpty()) {
                return false;
            }
            if (results.stream().anyMatch(result -> result instanceof RuntimeException)) {
                throw unavailable();
            }
            return true;
        } finally {
            if (active) {
                operations.discard();
            }
        }
    }

    private <T> T execute(Function<RedisOperations<String, String>, T> action) {
        try {
            return redis.execute(new SessionCallback<T>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> T execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> strings = (RedisOperations<String, String>) operations;
                    for (int attempt = 0; attempt < MAX_TRANSACTION_ATTEMPTS; attempt++) {
                        try {
                            T result = action.apply(strings);
                            if (result != null) {
                                return result;
                            }
                        } finally {
                            strings.unwatch();
                        }
                    }
                    throw unavailable();
                }
            });
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private DataAccessResourceFailureException unavailable() {
        return new DataAccessResourceFailureException("OAuth 임시 저장소를 사용할 수 없습니다.");
    }
}

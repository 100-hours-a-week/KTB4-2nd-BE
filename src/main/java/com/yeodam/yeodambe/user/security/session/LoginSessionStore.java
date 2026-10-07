package com.yeodam.yeodambe.user.security.session;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

@Component
public class LoginSessionStore {

    private static final Duration SESSION_TTL = Duration.ofDays(7);
    private static final int MAX_TRANSACTION_ATTEMPTS = 16;

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public LoginSessionStore(
            StringRedisTemplate redisTemplate,
            @Value("${auth.session.key-prefix}") String keyPrefix
    ) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
    }

    public void save(String sid, Long userId, String refreshTokenHash) {
        Objects.requireNonNull(userId, "userId");
        if (isBlank(sid) || isBlank(refreshTokenHash) || userId <= 0) {
            throw new IllegalArgumentException("로그인 세션 입력이 유효하지 않습니다.");
        }

        executeWithRetry(operations -> saveSession(operations, sid, userId, refreshTokenHash));
    }

    public Optional<LoginSession> findBySid(String sid) {
        if (isBlank(sid)) {
            return Optional.empty();
        }

        try {
            return readSession(redisTemplate, sessionKey(sid));
        } catch (DataAccessException exception) {
            throw storageUnavailable(exception);
        }
    }

    public Optional<String> findSidByRefreshTokenHash(String refreshTokenHash) {
        if (isBlank(refreshTokenHash)) {
            return Optional.empty();
        }

        return executeWithRetry(operations -> findSessionId(operations, refreshTokenHash));
    }

    public Optional<Long> rotate(String oldHash, String newHash) {
        if (isBlank(oldHash) || isBlank(newHash)) {
            return Optional.empty();
        }

        return executeWithRetry(operations -> rotateSession(operations, oldHash, newHash));
    }

    public void deleteBySid(String sid) {
        if (isBlank(sid)) {
            return;
        }

        executeWithRetry(operations -> deleteSession(operations, sid, null, null));
    }

    void deleteIfMatches(String sid, Long userId, String refreshTokenHash) {
        executeWithRetry(operations -> deleteSession(operations, sid, userId, refreshTokenHash));
    }

    public void deleteByUserId(Long userId) {
        if (userId == null) {
            return;
        }

        executeWithRetry(operations -> deleteUserSessions(operations, userId));
    }

    private Boolean saveSession(
            RedisOperations<String, String> operations,
            String sid,
            Long userId,
            String refreshTokenHash
    ) {
        String sessionKey = sessionKey(sid);
        String refreshKey = refreshKey(refreshTokenHash);
        String userSessionsKey = userSessionsKey(userId);
        operations.watch(List.of(sessionKey, refreshKey, userSessionsKey));
        validateUserIndex(operations, userSessionsKey);

        if (Boolean.TRUE.equals(operations.hasKey(sessionKey))
                || Boolean.TRUE.equals(operations.hasKey(refreshKey))) {
            throw new DataIntegrityViolationException("로그인 세션 식별자가 중복되었습니다.");
        }

        long now = currentRedisTimeMillis();
        Instant expiresAt = Instant.ofEpochMilli(now + SESSION_TTL.toMillis());
        boolean committed = executeTransaction(operations, writes -> {
            writes.opsForHash().putAll(sessionKey, Map.of(
                    "userId", userId.toString(),
                    "refreshTokenHash", refreshTokenHash,
                    "expiresAt", Long.toString(expiresAt.toEpochMilli())
            ));
            writes.expireAt(sessionKey, expiresAt);
            writes.opsForValue().set(refreshKey, sid);
            writes.expireAt(refreshKey, expiresAt);
            updateUserIndex(writes, userSessionsKey, sid, now);
        });
        if (!committed) {
            return null;
        }
        return true;
    }

    private Optional<String> findSessionId(RedisOperations<String, String> operations, String refreshTokenHash) {
        String refreshKey = refreshKey(refreshTokenHash);
        operations.watch(refreshKey);
        String sid = operations.opsForValue().get(refreshKey);
        if (sid == null) {
            return Optional.empty();
        }

        String sessionKey = sessionKey(sid);
        operations.watch(sessionKey);
        Optional<LoginSession> session = readSession(operations, sessionKey);
        boolean matches = session.isPresent() && refreshTokenHash.equals(session.get().refreshTokenHash());
        boolean committed = executeTransaction(operations, writes -> {
            if (matches) {
                writes.hasKey(sessionKey);
            } else {
                writes.delete(refreshKey);
            }
        });
        if (!committed) {
            return null;
        }
        if (!matches) {
            return Optional.empty();
        }
        return Optional.of(sid);
    }

    private Optional<Long> rotateSession(RedisOperations<String, String> operations, String oldHash, String newHash) {
        String oldRefreshKey = refreshKey(oldHash);
        operations.watch(oldRefreshKey);
        String sid = operations.opsForValue().get(oldRefreshKey);
        if (sid == null) {
            return Optional.empty();
        }

        String sessionKey = sessionKey(sid);
        operations.watch(sessionKey);
        Optional<LoginSession> session = readSession(operations, sessionKey);
        if (session.isEmpty() || !oldHash.equals(session.get().refreshTokenHash())) {
            return Optional.empty();
        }

        Long userId = session.get().userId();
        String userSessionsKey = userSessionsKey(userId);
        String newRefreshKey = refreshKey(newHash);
        operations.watch(List.of(userSessionsKey, newRefreshKey));
        validateUserIndex(operations, userSessionsKey);
        if (Boolean.TRUE.equals(operations.hasKey(newRefreshKey))) {
            throw new DataAccessResourceFailureException("Refresh Token 식별자가 중복되었습니다.");
        }

        long now = currentRedisTimeMillis();
        Instant expiresAt = Instant.ofEpochMilli(now + SESSION_TTL.toMillis());
        boolean committed = executeTransaction(operations, writes -> {
            writes.opsForHash().putAll(sessionKey, Map.of(
                    "refreshTokenHash", newHash,
                    "expiresAt", Long.toString(expiresAt.toEpochMilli())
            ));
            writes.expireAt(sessionKey, expiresAt);
            writes.delete(oldRefreshKey);
            writes.opsForValue().set(newRefreshKey, sid);
            writes.expireAt(newRefreshKey, expiresAt);
            updateUserIndex(writes, userSessionsKey, sid, now);
        });
        if (!committed) {
            return null;
        }
        return Optional.of(userId);
    }

    private Boolean deleteSession(
            RedisOperations<String, String> operations,
            String sid,
            Long expectedUserId,
            String expectedHash
    ) {
        String sessionKey = sessionKey(sid);
        operations.watch(sessionKey);
        Optional<LoginSession> session = readSession(operations, sessionKey);
        if (session.isEmpty()) {
            return true;
        }

        LoginSession currentSession = session.get();
        if (expectedUserId != null && (!expectedUserId.equals(currentSession.userId())
                || !expectedHash.equals(currentSession.refreshTokenHash()))) {
            return true;
        }

        String userSessionsKey = userSessionsKey(currentSession.userId());
        String refreshKey = refreshKey(currentSession.refreshTokenHash());
        operations.watch(List.of(userSessionsKey, refreshKey));
        validateUserIndex(operations, userSessionsKey);
        Long sessionCount = operations.opsForZSet().zCard(userSessionsKey);
        boolean lastSession = Long.valueOf(1).equals(sessionCount)
                && operations.opsForZSet().score(userSessionsKey, sid) != null;
        boolean committed = executeTransaction(operations, writes -> {
            writes.delete(List.of(sessionKey, refreshKey));
            writes.opsForZSet().remove(userSessionsKey, sid);
            if (Long.valueOf(0).equals(sessionCount) || lastSession) {
                writes.delete(userSessionsKey);
            }
        });
        if (!committed) {
            return null;
        }
        return true;
    }

    private Boolean deleteUserSessions(RedisOperations<String, String> operations, Long userId) {
        String userSessionsKey = userSessionsKey(userId);
        operations.watch(userSessionsKey);
        validateUserIndex(operations, userSessionsKey);
        Set<String> sids = operations.opsForZSet().range(userSessionsKey, 0, -1);
        List<String> sessionKeys = new ArrayList<>();
        if (sids != null) {
            for (String sid : sids) {
                sessionKeys.add(sessionKey(sid));
            }
        }
        if (!sessionKeys.isEmpty()) {
            operations.watch(sessionKeys);
        }

        List<String> keysToDelete = new ArrayList<>();
        keysToDelete.add(userSessionsKey);
        for (String sessionKey : sessionKeys) {
            Optional<LoginSession> session = readSession(operations, sessionKey);
            if (session.isPresent() && userId.equals(session.get().userId())) {
                keysToDelete.add(sessionKey);
                keysToDelete.add(refreshKey(session.get().refreshTokenHash()));
            }
        }

        boolean committed = executeTransaction(operations, writes -> writes.delete(keysToDelete));
        if (!committed) {
            return null;
        }
        return true;
    }

    private Optional<LoginSession> readSession(RedisOperations<String, String> operations, String sessionKey) {
        List<Object> fields = operations.opsForHash().multiGet(sessionKey, List.of("userId", "refreshTokenHash"));
        String userId = (String) fields.get(0);
        String refreshTokenHash = (String) fields.get(1);
        if (userId == null && refreshTokenHash == null) {
            return Optional.empty();
        }
        if (userId == null || refreshTokenHash == null) {
            throw new DataAccessResourceFailureException("로그인 세션 데이터가 불완전합니다.");
        }

        try {
            return Optional.of(new LoginSession(Long.valueOf(userId), refreshTokenHash));
        } catch (NumberFormatException exception) {
            throw new DataAccessResourceFailureException("로그인 세션 데이터가 유효하지 않습니다.", exception);
        }
    }

    private void validateUserIndex(RedisOperations<String, String> operations, String userSessionsKey) {
        DataType type = operations.type(userSessionsKey);
        if (type != DataType.NONE && type != DataType.ZSET) {
            throw new DataAccessResourceFailureException("로그인 세션 인덱스가 유효하지 않습니다.");
        }
    }

    private long currentRedisTimeMillis() {
        Long now = redisTemplate.execute((RedisCallback<Long>) connection ->
                connection.serverCommands().time(TimeUnit.MILLISECONDS));
        if (now == null) {
            throw new DataAccessResourceFailureException("인증 저장소의 시간을 조회할 수 없습니다.");
        }
        return now;
    }

    private void updateUserIndex(RedisOperations<String, String> operations, String userSessionsKey, String sid, long now) {
        Instant expiresAt = Instant.ofEpochMilli(now + SESSION_TTL.toMillis());
        operations.opsForZSet().removeRangeByScore(userSessionsKey, Double.NEGATIVE_INFINITY, now);
        operations.opsForZSet().add(userSessionsKey, sid, expiresAt.toEpochMilli());
        operations.expireAt(userSessionsKey, expiresAt);
    }

    private boolean executeTransaction(
            RedisOperations<String, String> operations,
            Consumer<RedisOperations<String, String>> commands
    ) {
        boolean transactionActive = false;
        try {
            operations.multi();
            transactionActive = true;
            commands.accept(operations);
            List<Object> results = operations.exec();
            transactionActive = false;
            if (results == null || results.isEmpty()) {
                return false;
            }
            for (Object result : results) {
                if (result instanceof RuntimeException exception) {
                    throw exception;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            if (transactionActive) {
                try {
                    operations.discard();
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
    }

    private <T> T executeWithRetry(Function<RedisOperations<String, String>, T> operation) {
        try {
            return redisTemplate.execute(new SessionCallback<T>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> T execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> stringOperations = (RedisOperations<String, String>) operations;
                    for (int attempt = 0; attempt < MAX_TRANSACTION_ATTEMPTS; attempt++) {
                        try {
                            // null은 WATCH 충돌로 EXEC가 취소된 경우에만 반환한다.
                            T result = operation.apply(stringOperations);
                            if (result != null) {
                                return result;
                            }
                        } finally {
                            stringOperations.unwatch();
                        }
                    }
                    throw new DataAccessResourceFailureException("로그인 세션 변경 충돌이 반복되었습니다.");
                }
            });
        } catch (DataIntegrityViolationException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw storageUnavailable(exception);
        }
    }

    private DataAccessResourceFailureException storageUnavailable(DataAccessException exception) {
        return new DataAccessResourceFailureException("인증 저장소에 연결할 수 없습니다.", exception);
    }

    private String sessionKey(String sid) {
        return keyPrefix + "session:" + sid;
    }

    private String refreshKey(String refreshTokenHash) {
        return keyPrefix + "refresh:" + refreshTokenHash;
    }

    private String userSessionsKey(Long userId) {
        return keyPrefix + "user-sessions:" + userId;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

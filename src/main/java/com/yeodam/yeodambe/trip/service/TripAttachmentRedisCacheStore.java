package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentReadRejectedException;
import com.yeodam.yeodambe.trip.config.TripAttachmentCacheProperties;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageKey;
import com.yeodam.yeodambe.trip.service.request.cursor.AttachmentCursor;
import com.yeodam.yeodambe.trip.service.response.AttachmentReadRefresh;
import com.yeodam.yeodambe.trip.service.response.CachedAttachmentResponse;
import com.yeodam.yeodambe.trip.service.response.CachedTripReadAccessSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentListResponse;
import com.yeodam.yeodambe.trip.service.response.TripReadAccessSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

@Component
@Slf4j
public class TripAttachmentRedisCacheStore {

    private static final long CLOCK_SAFETY_MARGIN_MILLIS = 5L;
    private static final int MAX_TRANSACTION_ATTEMPTS = 3;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final TripAttachmentCacheProperties properties;
    private final LongSupplier nanoTime;

    @Autowired
    public TripAttachmentRedisCacheStore(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            TripAttachmentCacheProperties properties
    ) {
        this(redis, objectMapper, properties, System::nanoTime);
    }

    TripAttachmentRedisCacheStore(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            TripAttachmentCacheProperties properties,
            LongSupplier nanoTime
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.nanoTime = nanoTime;
    }

    public Optional<CachedAttachmentResponse> findResponse(AttachmentPageKey query) {
        long started = nanoTime.getAsLong();
        try {
            ReadReply reply = readSnapshot(responseKey(query));
            if (reply.value() == null) {
                return Optional.empty();
            }
            StoredResponse stored = objectMapper.readValue(reply.value(), StoredResponse.class);
            if (!matchesEnvelope(stored, query)) {
                return Optional.empty();
            }
            CachedAttachmentResponse cached = toLocalResponse(
                    stored, reply.redisNowMillis(), reply.remainingTtlMillis(), started);
            return cached.isUsableAt(nanoTime.getAsLong()) ? Optional.of(cached) : Optional.empty();
        } catch (RuntimeException exception) {
            throw storeUnavailable("read", query.tripId(), exception);
        }
    }

    public AttachmentReadRefresh beginResponseRefresh(AttachmentPageKey query, RLock ownerLock) {
        return beginRefresh(query.tripId(), responseKey(query), responseLockKey(query), ownerLock);
    }

    private AttachmentReadRefresh beginRefresh(Long tripId, String valueKey, String lockKey, RLock ownerLock) {
        long started = nanoTime.getAsLong();
        long redisStarted;
        try {
            if (!lockKey.equals(ownerLock.getName()) || !ownerLock.isHeldByCurrentThread()) {
                throw new AttachmentReadRejectedException(AttachmentReadRejectedException.Reason.OWNER_LOST);
            }
            redisStarted = currentRedisTimeMillis();
        } catch (AttachmentReadRejectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw storeUnavailable("begin", tripId, exception);
        }
        long roundTripMillis = Math.ceilDiv(nanoTime.getAsLong() - started, 1_000_000L);
        return new AttachmentReadRefresh(valueKey, lockKey, ownerLock, digest(UUID.randomUUID().toString()), started,
                redisStarted, redisStarted + 500L - roundTripMillis - CLOCK_SAFETY_MARGIN_MILLIS);
    }

    public PublishOutcome publishResponse(
            AttachmentPageKey query,
            AttachmentReadRefresh refresh,
            long generation,
            TripAttachmentListResponse response
    ) {
        try {
            requireRefreshKeys(refresh, responseKey(query), responseLockKey(query));
            StoredResponse stored = new StoredResponse(
                    properties.getResponseSchemaVersion(), query, refresh.refreshId(), generation,
                    refresh.startedAtEpochMillis(), refresh.expiresAtEpochMillis(), response);
            if (!matchesEnvelope(stored, query)) {
                throw new IllegalArgumentException(
                        TripInternalErrorMessage.PHOTO_LIST_RESPONSE_CACHE_PAYLOAD_INVALID.message()
                );
            }
            String payload = objectMapper.writeValueAsString(stored);
            return executeWithRetry(operations -> publishIfOwned(operations, refresh, payload));
        } catch (RuntimeException exception) {
            if (exception instanceof DataAccessException && confirmPublished(query, refresh, generation, response)) {
                return PublishOutcome.ALREADY_PUBLISHED;
            }
            throw storeUnavailable("publish", query.tripId(), exception);
        }
    }

    private boolean confirmPublished(
            AttachmentPageKey query,
            AttachmentReadRefresh refresh,
            long generation,
            TripAttachmentListResponse response
    ) {
        try {
            return findResponse(query).filter((CachedAttachmentResponse cached) ->
                    cached.refreshId().equals(refresh.refreshId()) && cached.generation() == generation
                            && cached.response().equals(response)).isPresent();
        } catch (RuntimeException confirmationFailure) {
            return false;
        }
    }

    public Optional<CachedTripReadAccessSnapshot> findAccess(Long tripId) {
        long started = nanoTime.getAsLong();
        try {
            ReadReply reply = readSnapshot(accessValueKey(tripId));
            if (reply.value() == null) {
                return Optional.empty();
            }
            StoredAccess stored = objectMapper.readValue(reply.value(), StoredAccess.class);
            if (!matchesAccess(stored, tripId)) {
                return Optional.empty();
            }
            long remaining = Math.max(0L, Math.min(reply.remainingTtlMillis(),
                    stored.expiresAtEpochMillis() - reply.redisNowMillis()));
            CachedTripReadAccessSnapshot cached = new CachedTripReadAccessSnapshot(stored.snapshot(),
                    started + TimeUnit.MILLISECONDS.toNanos(remaining), stored.snapshotId());
            return cached.isUsableAt(nanoTime.getAsLong()) ? Optional.of(cached) : Optional.empty();
        } catch (RuntimeException exception) {
            throw storeUnavailable("access_read", tripId, exception);
        }
    }

    public AttachmentReadRefresh beginAccessRefresh(Long tripId, RLock ownerLock) {
        return beginRefresh(tripId, accessValueKey(tripId), accessLockKey(tripId), ownerLock);
    }

    public PublishOutcome publishAccess(Long tripId, AttachmentReadRefresh refresh, TripReadAccessSnapshot snapshot) {
        try {
            requireRefreshKeys(refresh, accessValueKey(tripId), accessLockKey(tripId));
            StoredAccess stored = new StoredAccess(
                    properties.getAccessSchemaVersion(), snapshot, refresh.refreshId(),
                    refresh.startedAtEpochMillis(), refresh.expiresAtEpochMillis());
            if (!matchesAccess(stored, tripId)) {
                throw new IllegalArgumentException(TripInternalErrorMessage.PHOTO_LIST_ACCESS_PAYLOAD_INVALID.message());
            }
            String payload = objectMapper.writeValueAsString(stored);
            return executeWithRetry(operations -> publishIfOwned(operations, refresh, payload));
        } catch (RuntimeException exception) {
            if (exception instanceof DataAccessException && confirmAccessPublished(tripId, refresh, snapshot)) {
                return PublishOutcome.ALREADY_PUBLISHED;
            }
            throw storeUnavailable("access_publish", tripId, exception);
        }
    }

    private boolean confirmAccessPublished(Long tripId, AttachmentReadRefresh refresh, TripReadAccessSnapshot snapshot) {
        try {
            return findAccess(tripId).filter(cached -> cached.snapshotId().equals(refresh.refreshId())
                    && cached.snapshot().equals(snapshot)).isPresent();
        } catch (RuntimeException confirmationFailure) {
            return false;
        }
    }

    private void requireRefreshKeys(AttachmentReadRefresh refresh, String valueKey, String lockKey) {
        if (!valueKey.equals(refresh.valueKey()) || !lockKey.equals(refresh.lockKey())) {
            throw new IllegalArgumentException(TripInternalErrorMessage.PHOTO_LIST_RESPONSE_CACHE_PAYLOAD_INVALID.message());
        }
    }

    private boolean matchesAccess(StoredAccess stored, Long tripId) {
        if (stored == null || stored.schemaVersion() != properties.getAccessSchemaVersion() || stored.snapshot() == null
                || stored.snapshotId() == null || !stored.snapshotId().matches("[a-f0-9]{64}")
                || stored.expiresAtEpochMillis() <= stored.startedAtEpochMillis()
                || stored.expiresAtEpochMillis() - stored.startedAtEpochMillis() > 500L) {
            return false;
        }
        TripReadAccessSnapshot snapshot = stored.snapshot();
        if (!Objects.equals(snapshot.tripId(), tripId) || tripId == null || tripId <= 0L
                || snapshot.ownerId() == null || snapshot.ownerId() <= 0L) {
            return false;
        }
        boolean validMembers = snapshot.members().stream().allMatch(member -> member.granteeUserId() != null
                && member.granteeUserId() > 0L && member.expiresAt() != null
                && (member.permission() == TripSharePermission.READ || member.permission() == TripSharePermission.WRITE));
        long distinctMembers = snapshot.members().stream().map(TripReadAccessSnapshot.Member::granteeUserId)
                .distinct().count();
        return validMembers && distinctMembers == snapshot.members().size();
    }

    private String accessValueKey(Long tripId) {
        return properties.getNamespace() + ":access:schema:" + properties.getAccessSchemaVersion()
                + ":{" + digest(properties.getAccessSchemaVersion() + "|" + tripId) + "}:value";
    }

    public String accessLockKey(Long tripId) {
        return properties.getNamespace() + ":access:schema:" + properties.getAccessSchemaVersion()
                + ":{" + digest(properties.getAccessSchemaVersion() + "|" + tripId) + "}:lock";
    }

    private ReadReply readSnapshot(String key) {
        return executeWithRetry(operations -> {
            List<Object> results = executeTransaction(operations, commands -> {
                commands.opsForValue().get(key);
                commands.getExpire(key, TimeUnit.MILLISECONDS);
                redis.execute((RedisCallback<Long>) connection ->
                        connection.serverCommands().time(TimeUnit.MILLISECONDS));
            });
            if (results == null) {
                return null;
            }
            if (results.size() != 3 || !(results.get(1) instanceof Long ttl)
                    || !(results.get(2) instanceof Long now)
                    || (results.getFirst() != null && !(results.getFirst() instanceof String))) {
                throw new IllegalStateException(TripInternalErrorMessage.PHOTO_LIST_RESPONSE_SNAPSHOT_INVALID.message());
            }
            return new ReadReply((String) results.getFirst(), ttl, now);
        });
    }

    private PublishOutcome publishIfOwned(
            RedisOperations<String, String> operations,
            AttachmentReadRefresh refresh,
            String payload
    ) {
        String valueKey = refresh.valueKey();
        String ownerKey = refresh.lockKey();
        operations.watch(List.of(valueKey, ownerKey));
        String existing = operations.opsForValue().get(valueKey);
        long now = currentRedisTimeMillis();
        if (payload.equals(existing) && refresh.expiresAtEpochMillis() > now) {
            Long ttl = operations.getExpire(valueKey, TimeUnit.MILLISECONDS);
            if (ttl != null && ttl > 0) {
                List<Object> results = executeTransaction(operations, commands -> commands.hasKey(valueKey));
                return results == null ? null : PublishOutcome.ALREADY_PUBLISHED;
            }
        }
        if (!ownerKey.equals(refresh.ownerLock().getName()) || !refresh.ownerLock().isHeldByCurrentThread()) {
            return PublishOutcome.OWNER_LOST;
        }
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(
                refresh.remainingLifetimeNanos(nanoTime.getAsLong()));
        long expiresAt = Math.min(refresh.expiresAtEpochMillis(), now + remainingMillis);
        if (expiresAt <= now) {
            return PublishOutcome.EXPIRED;
        }
        List<Object> results = executeTransaction(operations, commands -> {
            commands.opsForValue().set(valueKey, payload, Duration.ofMillis(expiresAt - now));
            commands.expireAt(valueKey, Instant.ofEpochMilli(expiresAt));
        });
        return results == null ? null : PublishOutcome.PUBLISHED;
    }

    private long currentRedisTimeMillis() {
        Long now = redis.execute((RedisCallback<Long>) connection ->
                connection.serverCommands().time(TimeUnit.MILLISECONDS));
        if (now == null) {
            throw new IllegalStateException(TripInternalErrorMessage.PHOTO_LIST_RESPONSE_REDIS_TIME_MISSING.message());
        }
        return now;
    }

    private List<Object> executeTransaction(
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
                return null;
            }
            for (Object result : results) {
                if (result instanceof RuntimeException exception) {
                    throw exception;
                }
            }
            return results;
        } catch (RuntimeException | Error exception) {
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
        return redis.execute(new SessionCallback<T>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> T execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> stringOperations = (RedisOperations<String, String>) operations;
                for (int attempt = 0; attempt < MAX_TRANSACTION_ATTEMPTS; attempt++) {
                    Throwable failure = null;
                    try {
                        T result = operation.apply(stringOperations);
                        if (result != null) {
                            return result;
                        }
                    } catch (RuntimeException | Error exception) {
                        failure = exception;
                        throw exception;
                    } finally {
                        clearWatch(stringOperations, failure);
                    }
                }
                throw new IllegalStateException(
                        TripInternalErrorMessage.PHOTO_LIST_RESPONSE_TRANSACTION_RETRIES_EXHAUSTED.message()
                );
            }
        });
    }

    private void clearWatch(RedisOperations<String, String> operations, Throwable failure) {
        try {
            operations.unwatch();
        } catch (RuntimeException cleanupFailure) {
            if (failure == null) {
                throw cleanupFailure;
            }
            failure.addSuppressed(cleanupFailure);
        }
    }

    private boolean matchesEnvelope(StoredResponse stored, AttachmentPageKey query) {
        if (stored == null) {
            return false;
        }
        TripAttachmentListResponse response = stored.response();
        if (stored.schemaVersion() != properties.getResponseSchemaVersion() || !Objects.equals(stored.key(), query)
                || stored.refreshId() == null || !stored.refreshId().matches("[a-f0-9]{64}")
                || stored.generation() < 0 || response == null || response.sequence() != stored.generation()
                || stored.expiresAtEpochMillis() <= stored.startedAtEpochMillis()
                || stored.expiresAtEpochMillis() - stored.startedAtEpochMillis() > 500L
                || response.items().size() > 18) {
            return false;
        }
        boolean validItems = response.items().stream().allMatch((TripAttachmentListResponse.Item item) ->
                item.tripAttachmentId() != null && item.tripAttachmentId() > 0 && validUrl(item.thumbnailUrl()));
        long distinctItems = response.items().stream()
                .map(TripAttachmentListResponse.Item::tripAttachmentId).distinct().count();
        if (!validItems || distinctItems != response.items().size()) {
            return false;
        }
        if (!response.hasNext()) {
            return response.nextCursor() == null;
        }
        if (response.items().isEmpty() || response.nextCursor() == null || response.nextCursor().isBlank()) {
            return false;
        }
        AttachmentCursor cursor = AttachmentCursor.decode(response.nextCursor(), query.tripId(), query.tripPlaceId(), null, objectMapper);
        return cursor != null && cursor.tripAttachmentId().equals(response.items().getLast().tripAttachmentId());
    }

    private boolean validUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private CachedAttachmentResponse toLocalResponse(
            StoredResponse stored,
            long redisNowMillis,
            long remainingTtlMillis,
            long readStartedAtNanos
    ) {
        long remaining = Math.max(0L, Math.min(remainingTtlMillis, stored.expiresAtEpochMillis() - redisNowMillis));
        return new CachedAttachmentResponse(stored.response(),
                readStartedAtNanos + TimeUnit.MILLISECONDS.toNanos(remaining), stored.refreshId(), stored.generation());
    }

    private String responseKey(AttachmentPageKey query) {
        return properties.getNamespace() + ":final-response:schema:" + properties.getResponseSchemaVersion()
                + ":{" + queryDigest(query) + "}:value";
    }

    public String responseLockKey(AttachmentPageKey query) {
        return properties.getNamespace() + ":final-response:schema:" + properties.getResponseSchemaVersion()
                + ":{" + queryDigest(query) + "}:lock";
    }

    private String queryDigest(AttachmentPageKey query) {
        return digest(properties.getResponseSchemaVersion() + "|" + query.tripId() + "|"
                + query.tripPlaceId() + "|" + query.cursor());
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.PHOTO_LIST_RESPONSE_KEY_UNAVAILABLE.message(), exception
            );
        }
    }

    private AttachmentReadRejectedException storeUnavailable(
            String operation,
            Long tripId,
            RuntimeException cause
    ) {
        logStoreFailure(operation, tripId, cause);
        AttachmentReadRejectedException failure = new AttachmentReadRejectedException(
                AttachmentReadRejectedException.Reason.STORE_UNAVAILABLE);
        failure.initCause(cause);
        return failure;
    }

    private void logStoreFailure(String operation, Long tripId, RuntimeException cause) {
        log.atWarn().addKeyValue("event", "photo_final_response_store_failure")
                .addKeyValue("operation", operation).addKeyValue("trip_id", tripId)
                .addKeyValue("failure", cause.getClass().getSimpleName())
                .log(TripInternalErrorMessage.PHOTO_LIST_RESPONSE_STORE_UNAVAILABLE.message());
    }

    public enum PublishOutcome {
        PUBLISHED,
        ALREADY_PUBLISHED,
        EXPIRED,
        OWNER_LOST
    }

    private record ReadReply(
            String value,
            long remainingTtlMillis,
            long redisNowMillis
    ) {
    }

    private record StoredAccess(
            int schemaVersion,
            TripReadAccessSnapshot snapshot,
            String snapshotId,
            long startedAtEpochMillis,
            long expiresAtEpochMillis
    ) {
    }

    private record StoredResponse(
            int schemaVersion,
            AttachmentPageKey key,
            String refreshId,
            long generation,
            long startedAtEpochMillis,
            long expiresAtEpochMillis,
            TripAttachmentListResponse response
    ) {
    }
}

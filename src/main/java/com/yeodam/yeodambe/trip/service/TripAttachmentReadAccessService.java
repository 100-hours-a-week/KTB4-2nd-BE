package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentReadRejectedException;
import com.yeodam.yeodambe.common.exception.PlaceFolderNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentRedisLockClient;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository.PhotoReadState;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.response.AttachmentReadRefresh;
import com.yeodam.yeodambe.trip.service.response.CachedTripReadAccessSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripReadAccessSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripReadPermit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

@Service
@Slf4j
public class TripAttachmentReadAccessService {

    private static final ZoneId SHARE_ZONE = ZoneId.of("Asia/Seoul");

    private final TripRepository trips;
    private final TripShareUserRepository shares;
    private final TripAttachmentRedisCacheStore store;
    private final TripAttachmentRedisLockClient locks;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final ConcurrentMap<Long, CompletableFuture<CachedTripReadAccessSnapshot>> pending = new ConcurrentHashMap<>();

    @Autowired
    public TripAttachmentReadAccessService(
            TripRepository trips,
            TripShareUserRepository shares,
            TripAttachmentRedisCacheStore store,
            TripAttachmentRedisLockClient locks,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this(trips, shares, store, locks, transactionManager, clock, System::nanoTime);
    }

    TripAttachmentReadAccessService(
            TripRepository trips,
            TripShareUserRepository shares,
            TripAttachmentRedisCacheStore store,
            TripAttachmentRedisLockClient locks,
            PlatformTransactionManager transactionManager,
            Clock clock,
            LongSupplier nanoTime
    ) {
        this.trips = trips;
        this.shares = shares;
        this.store = store;
        this.locks = locks;
        this.clock = clock;
        this.nanoTime = nanoTime;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public TripReadPermit requireReadable(Long userId, Long tripId, AttachmentReadContext context) {
        requireNoActiveTransaction();
        context.requireWaitBudget();
        Optional<CachedTripReadAccessSnapshot> cached = usableSnapshot(tripId);
        if (cached.isPresent()) {
            try {
                return evaluate(cached.get().snapshot(), userId, cached.get().validUntilNanos(), cached.get().snapshotId());
            } catch (SnapshotExpiredException exception) {
                context.onCacheMiss();
            }
        }
        context.onCacheMiss();
        while (context.remainingWaitNanos() > 0L) {
            context.requireWaitBudget();
            CachedTripReadAccessSnapshot snapshot = readMiss(tripId, context);
            try {
                return evaluate(snapshot.snapshot(), userId, snapshot.validUntilNanos(), snapshot.snapshotId());
            } catch (SnapshotExpiredException exception) {
                context.requireWaitBudget();
            }
        }
        throw rejected(AttachmentReadRejectedException.Reason.TIMEOUT);
    }

    public TripReadPermit revalidate(TripReadPermit permit, AttachmentReadContext context) {
        requireNoActiveTransaction();
        context.requireWaitBudget();
        Instant checkedAt = clock.instant();
        if (permit.isUsableAt(nanoTime.getAsLong(), checkedAt)) {
            return permit;
        }
        return requireReadable(permit.userId(), permit.tripId(), context);
    }

    private CachedTripReadAccessSnapshot readMiss(Long tripId, AttachmentReadContext context) {
        while (context.remainingWaitNanos() > 0L) {
            context.requireWaitBudget();
            CompletableFuture<CachedTripReadAccessSnapshot> proposed = new CompletableFuture<>();
            CompletableFuture<CachedTripReadAccessSnapshot> existing = pending.putIfAbsent(tripId, proposed);
            CompletableFuture<CachedTripReadAccessSnapshot> flight = existing == null ? proposed : existing;
            if (existing == null) {
                try {
                    flight.complete(refreshOrAwait(tripId, context));
                } catch (AttachmentReadRejectedException exception) {
                    if (exception.reason() == AttachmentReadRejectedException.Reason.TIMEOUT
                            || exception.reason() == AttachmentReadRejectedException.Reason.INTERRUPTED
                            || exception.reason() == AttachmentReadRejectedException.Reason.RETRY_EXHAUSTED) {
                        pending.remove(tripId, flight);
                        flight.completeExceptionally(new RetiredFlightException());
                    } else {
                        flight.completeExceptionally(exception);
                    }
                    throw exception;
                } catch (SnapshotExpiredException exception) {
                    flight.completeExceptionally(exception);
                } catch (RuntimeException | Error exception) {
                    flight.completeExceptionally(exception);
                    throw exception;
                } finally {
                    pending.remove(tripId, flight);
                }
            }
            try {
                CachedTripReadAccessSnapshot result = await(flight, context);
                context.requireWaitBudget();
                if (result.isUsableAt(nanoTime.getAsLong())) {
                    return result;
                }
            } catch (RetiredFlightException | SnapshotExpiredException exception) {
                context.requireWaitBudget();
            }
        }
        throw rejected(AttachmentReadRejectedException.Reason.TIMEOUT);
    }

    private CachedTripReadAccessSnapshot refreshOrAwait(Long tripId, AttachmentReadContext context) {
        while (context.remainingWaitNanos() > 0L) {
            context.requireWaitBudget();
            Optional<CachedTripReadAccessSnapshot> cached = usableSnapshot(tripId);
            if (cached.isPresent()) {
                return cached.get();
            }
            context.requireWaitBudget();
            Optional<RLock> lock = locks.tryAcquire(tripId, store.accessLockKey(tripId), context.remainingWaitNanos());
            if (lock.isPresent()) {
                return refresh(tripId, lock.get(), context);
            }
        }
        throw rejected(AttachmentReadRejectedException.Reason.TIMEOUT);
    }

    private CachedTripReadAccessSnapshot refresh(Long tripId, RLock lock, AttachmentReadContext context) {
        try {
            Optional<CachedTripReadAccessSnapshot> cached = usableSnapshot(tripId);
            if (cached.isPresent()) {
                return cached.get();
            }
            context.beginAccessRefresh();
            AttachmentReadRefresh refresh = store.beginAccessRefresh(tripId, lock);
            context.requireWaitBudget();
            long validUntil = refresh.startedAtNanos() + TimeUnit.MILLISECONDS.toNanos(
                    refresh.expiresAtEpochMillis() - refresh.startedAtEpochMillis());
            if (validUntil - nanoTime.getAsLong() <= 0L) {
                throw new SnapshotExpiredException();
            }
            TripReadAccessSnapshot snapshot = loadSnapshot(tripId);
            TripAttachmentRedisCacheStore.PublishOutcome outcome = store.publishAccess(tripId, refresh, snapshot);
            if (outcome == TripAttachmentRedisCacheStore.PublishOutcome.OWNER_LOST) {
                throw rejected(AttachmentReadRejectedException.Reason.OWNER_LOST);
            }
            if (outcome == TripAttachmentRedisCacheStore.PublishOutcome.EXPIRED) {
                throw new SnapshotExpiredException();
            }
            context.requireWaitBudget();
            return new CachedTripReadAccessSnapshot(snapshot, validUntil, refresh.refreshId());
        } finally {
            releaseQuietly(tripId, lock);
        }
    }

    private TripReadAccessSnapshot loadSnapshot(Long tripId) {
        return Objects.requireNonNull(transaction.execute(status -> {
            PhotoReadState state = trips.findPhotoReadStateById(tripId).orElseThrow(PlaceFolderNotFoundException::new);
            LocalDateTime checkedAt = LocalDateTime.ofInstant(clock.instant(), SHARE_ZONE);
            List<TripReadAccessSnapshot.Member> members = shares.findReadableGrantsByTripIdAndOwnerId(
                    tripId, state.getOwnerId(), checkedAt).stream()
                    .map(row -> new TripReadAccessSnapshot.Member(row.getGranteeUserId(), row.getPermission(),
                            row.getExpiresAt().atZone(SHARE_ZONE).toInstant()))
                    .toList();
            return new TripReadAccessSnapshot(state.getTripId(), state.getOwnerId(), members);
        }), TripInternalErrorMessage.PHOTO_LIST_READ_RESULT_MISSING.message());
    }

    private TripReadPermit evaluate(TripReadAccessSnapshot snapshot, Long userId, long validUntil, String snapshotId) {
        long nowNanos = nanoTime.getAsLong();
        Instant now = clock.instant();
        if (validUntil - nanoTime.getAsLong() <= 0L) {
            throw new SnapshotExpiredException();
        }
        if (Objects.equals(snapshot.ownerId(), userId)) {
            return new TripReadPermit(userId, snapshot.tripId(), snapshotId, validUntil, null);
        }
        TripReadAccessSnapshot.Member member = snapshot.members().stream()
                .filter(value -> Objects.equals(value.granteeUserId(), userId))
                .filter(value -> value.permission() == TripSharePermission.READ || value.permission() == TripSharePermission.WRITE)
                .filter(value -> value.expiresAt().isAfter(now))
                .findFirst().orElseThrow(PlaceFolderNotFoundException::new);
        Duration shareRemaining = Duration.between(now, member.expiresAt());
        long remaining = validUntil - nowNanos;
        if (shareRemaining.compareTo(Duration.ofNanos(remaining)) < 0) {
            validUntil = nowNanos + shareRemaining.toNanos();
        }
        if (validUntil - nanoTime.getAsLong() <= 0L) {
            throw new SnapshotExpiredException();
        }
        return new TripReadPermit(userId, snapshot.tripId(), snapshotId, validUntil, member.expiresAt());
    }

    private Optional<CachedTripReadAccessSnapshot> usableSnapshot(Long tripId) {
        return store.findAccess(tripId).filter(value -> value.isUsableAt(nanoTime.getAsLong()));
    }

    private CachedTripReadAccessSnapshot await(
            CompletableFuture<CachedTripReadAccessSnapshot> flight,
            AttachmentReadContext context
    ) {
        context.requireWaitBudget();
        try {
            return flight.get(context.remainingWaitNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw rejected(AttachmentReadRejectedException.Reason.INTERRUPTED);
        } catch (TimeoutException exception) {
            throw rejected(AttachmentReadRejectedException.Reason.TIMEOUT);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException failure) {
                throw failure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(TripInternalErrorMessage.PHOTO_LIST_RESPONSE_LEADER_FAILED.message(), cause);
        }
    }

    private void requireNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(TripInternalErrorMessage.PHOTO_LIST_RESPONSE_TRANSACTION_ACTIVE.message());
        }
    }

    private void releaseQuietly(Long tripId, RLock lock) {
        try {
            locks.release(tripId, store.accessLockKey(tripId), lock);
        } catch (RuntimeException exception) {
            log.atWarn().addKeyValue("event", "photo_access_release_failure").addKeyValue("trip_id", tripId)
                    .addKeyValue("failure", exception.getClass().getSimpleName())
                    .log(TripInternalErrorMessage.PHOTO_LIST_RESPONSE_LOCK_RELEASE_FAILED.message());
        }
    }

    private AttachmentReadRejectedException rejected(AttachmentReadRejectedException.Reason reason) {
        return new AttachmentReadRejectedException(reason);
    }

    private static final class RetiredFlightException extends RuntimeException {
        private RetiredFlightException() {
            super(TripInternalErrorMessage.PHOTO_LIST_RESPONSE_LEADER_WAIT_EXPIRED.message());
        }
    }

    private static final class SnapshotExpiredException extends RuntimeException {
        private SnapshotExpiredException() {
            super(TripInternalErrorMessage.PHOTO_LIST_ACCESS_LIFETIME_EXPIRED.message());
        }
    }
}

package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentReadRejectedException;
import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import com.yeodam.yeodambe.common.exception.PlaceFolderNotFoundException;
import com.yeodam.yeodambe.common.exception.TripDetailNotAvailableException;
import com.yeodam.yeodambe.trip.client.TripAttachmentRedisLockClient;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.service.TripAttachmentRedisCacheStore.PublishOutcome;
import com.yeodam.yeodambe.trip.service.TripAttachmentReadEvent.Outcome;
import com.yeodam.yeodambe.trip.service.TripAttachmentReadEvent.Phase;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageKey;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageQuery;
import com.yeodam.yeodambe.trip.service.response.AttachmentPageData;
import com.yeodam.yeodambe.trip.service.response.AttachmentReadRefresh;
import com.yeodam.yeodambe.trip.service.response.CachedAttachmentResponse;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentListResponse;
import com.yeodam.yeodambe.trip.service.response.TripReadPermit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;

@Service
@Slf4j
public class TripAttachmentResponseCacheService {

    private final TripAttachmentPageReader reader;
    private final TripAttachmentRedisCacheStore responseCache;
    private final TripAttachmentRedisLockClient locks;
    private final TripAttachmentReadAccessService access;
    private final ApplicationEventPublisher events;
    private final LongSupplier nanoTime;
    private final ConcurrentMap<AttachmentPageKey, Flight> pending = new ConcurrentHashMap<>();

    @Autowired
    public TripAttachmentResponseCacheService(
            TripAttachmentPageReader reader,
            TripAttachmentRedisCacheStore responseCache,
            TripAttachmentRedisLockClient locks,
            TripAttachmentReadAccessService access,
            ApplicationEventPublisher events
    ) {
        this(reader, responseCache, locks, access, events, System::nanoTime);
    }

    TripAttachmentResponseCacheService(
            TripAttachmentPageReader reader,
            TripAttachmentRedisCacheStore responseCache,
            TripAttachmentRedisLockClient locks,
            TripAttachmentReadAccessService access,
            ApplicationEventPublisher events,
            LongSupplier nanoTime
    ) {
        this.reader = reader;
        this.responseCache = responseCache;
        this.locks = locks;
        this.access = access;
        this.events = events;
        this.nanoTime = nanoTime;
    }

    public TripAttachmentListResponse read(
            AttachmentPageQuery query,
            Function<AttachmentPageData, TripAttachmentListResponse> mapper
    ) {
        requireNoActiveTransaction();
        long started = nanoTime.getAsLong();
        try {
            return readResponse(query, mapper);
        } catch (AttachmentReadRejectedException exception) {
            emitReadEvent(Phase.REJECTION, Outcome.valueOf(exception.reason().name()), started, null);
            throw exception;
        }
    }

    private TripAttachmentListResponse readResponse(
            AttachmentPageQuery query,
            Function<AttachmentPageData, TripAttachmentListResponse> mapper
    ) {
        AttachmentReadContext context = new AttachmentReadContext(nanoTime);
        TripReadPermit permit = access.requireReadable(query.userId(), query.tripId(), context);
        AttachmentPageKey key;
        try {
            key = reader.normalizeKey(query);
        } catch (InvalidCursorException exception) {
            validateMalformedTarget(query, permit, context);
            throw exception;
        }
        do {
            CachedAttachmentResponse response = readShared(key, mapper, context);
            permit = access.revalidate(permit, context);
            if (!Objects.equals(permit.userId(), query.userId()) || !Objects.equals(permit.tripId(), query.tripId())) {
                throw new IllegalStateException(TripInternalErrorMessage.PHOTO_LIST_ACCESS_PERMIT_MISMATCH.message());
            }
            context.requireWaitBudget();
            long returningAtNanos = nanoTime.getAsLong();
            if (permit.validUntilNanos() - returningAtNanos > 0L && response.isUsableAt(returningAtNanos)) {
                return response.response();
            }
            context.onCacheMiss();
        } while (context.remainingWaitNanos() > 0L);
        throw rejected(AttachmentReadRejectedException.Reason.TIMEOUT);
    }

    private void validateMalformedTarget(AttachmentPageQuery query, TripReadPermit permit, AttachmentReadContext context) {
        try {
            reader.validateTarget(query.tripId(), query.tripPlaceId());
        } catch (PlaceFolderNotFoundException | TripDetailNotAvailableException exception) {
            access.revalidate(permit, context);
            throw exception;
        }
        access.revalidate(permit, context);
    }

    private CachedAttachmentResponse readShared(
            AttachmentPageKey key,
            Function<AttachmentPageData, TripAttachmentListResponse> mapper,
            AttachmentReadContext context
    ) {
        context.requireWaitBudget();
        long started = nanoTime.getAsLong();
        Optional<CachedAttachmentResponse> cached = usableCachedResponse(key);
        if (cached.isPresent()) {
            emitReadEvent(Phase.RETURN, Outcome.HIT, started, cached.get().refreshId());
            emitReadEvent(Phase.FRESHNESS, Outcome.SUCCESS,
                    cached.get().validUntilNanos() - TimeUnit.MILLISECONDS.toNanos(500L), cached.get().refreshId());
            return cached.get();
        }
        context.onCacheMiss();
        emitReadEvent(Phase.RETURN, Outcome.MISS, started, null);
        return readMiss(key, mapper, context);
    }

    private CachedAttachmentResponse readMiss(
            AttachmentPageKey query,
            Function<AttachmentPageData, TripAttachmentListResponse> responseMapper,
            AttachmentReadContext context
    ) {
        while (context.remainingWaitNanos() > 0) {
            context.requireWaitBudget();
            Flight flight = joinOrCreate(query);
            boolean leader = flight.claimed.compareAndSet(false, true);
            emitReadEvent(Phase.ROLE, leader ? Outcome.LEADER : Outcome.FOLLOWER, nanoTime.getAsLong(), null);
            if (leader) {
                try {
                    CachedAttachmentResponse result = refreshOrAwait(
                            query, responseMapper, context);
                    flight.result.complete(result);
                } catch (AttachmentReadRejectedException exception) {
                    if (exception.reason() == AttachmentReadRejectedException.Reason.TIMEOUT
                            || exception.reason() == AttachmentReadRejectedException.Reason.INTERRUPTED
                            || exception.reason() == AttachmentReadRejectedException.Reason.RETRY_EXHAUSTED) {
                        retireFlight(query, flight);
                    } else {
                        flight.result.completeExceptionally(exception);
                    }
                    throw exception;
                } catch (ResponseExpiredException exception) {
                    flight.result.completeExceptionally(exception);
                } catch (RuntimeException | Error exception) {
                    flight.result.completeExceptionally(exception);
                    throw exception;
                } finally {
                    pending.remove(query, flight);
                }
            }
            try {
                CachedAttachmentResponse result = awaitLocal(flight, context);
                emitReadEvent(Phase.FRESHNESS, Outcome.SUCCESS,
                        result.validUntilNanos() - TimeUnit.MILLISECONDS.toNanos(500L), result.refreshId());
                return requireUsableResponse(result, context);
            } catch (LocalFlightRetiredException exception) {
                context.requireWaitBudget();
                emitReadEvent(Phase.ROLE, Outcome.REJOINED, nanoTime.getAsLong(), null);
            } catch (ResponseExpiredException exception) {
                context.requireWaitBudget();
            }
        }
        throw rejected(AttachmentReadRejectedException.Reason.TIMEOUT);
    }

    private CachedAttachmentResponse refreshOrAwait(
            AttachmentPageKey query,
            Function<AttachmentPageData, TripAttachmentListResponse> responseMapper,
            AttachmentReadContext context
    ) {
        while (context.remainingWaitNanos() > 0) {
            context.requireWaitBudget();
            Optional<CachedAttachmentResponse> cached = usableCachedResponse(query);
            if (cached.isPresent()) {
                return cached.get();
            }
            context.requireWaitBudget();
            long started = nanoTime.getAsLong();
            Optional<RLock> owner = locks.tryAcquire(
                    query.tripId(), responseCache.responseLockKey(query), context.remainingWaitNanos());
            emitReadEvent(Phase.ATTEMPT, owner.isPresent() ? Outcome.ACQUIRED : Outcome.BUSY, started, null);
            if (owner.isPresent()) {
                return refresh(query, owner.get(), responseMapper, context);
            }
        }
        throw rejected(AttachmentReadRejectedException.Reason.TIMEOUT);
    }

    private CachedAttachmentResponse refresh(
            AttachmentPageKey query,
            RLock ownerLock,
            Function<AttachmentPageData, TripAttachmentListResponse> responseMapper,
            AttachmentReadContext context
    ) {
        try {
            Optional<CachedAttachmentResponse> cached = usableCachedResponse(query);
            if (cached.isPresent()) {
                return cached.get();
            }
            context.requireWaitBudget();
            context.beginResponseRefresh();
            AttachmentReadRefresh refresh = responseCache.beginResponseRefresh(query, ownerLock);
            context.requireWaitBudget();
            long lifetime = TimeUnit.MILLISECONDS.toNanos(
                    refresh.expiresAtEpochMillis() - refresh.startedAtEpochMillis());
            if (lifetime <= nanoTime.getAsLong() - refresh.startedAtNanos()) {
                throw new ResponseExpiredException();
            }
            long started = nanoTime.getAsLong();
            AttachmentPageData page = reader.readShared(query);
            emitReadEvent(Phase.EXECUTION, Outcome.SUCCESS, started, refresh.refreshId());
            TripAttachmentListResponse response = responseMapper.apply(page);
            PublishOutcome outcome = responseCache.publishResponse(query, refresh, page.sequence(), response);
            emitReadEvent(Phase.EXECUTION, outcome == PublishOutcome.EXPIRED ? Outcome.EXPIRED :
                    outcome == PublishOutcome.OWNER_LOST ? Outcome.OWNER_LOST : Outcome.PUBLISHED,
                    started, refresh.refreshId());
            if (outcome == PublishOutcome.OWNER_LOST) {
                throw rejected(AttachmentReadRejectedException.Reason.OWNER_LOST);
            }
            if (outcome == PublishOutcome.EXPIRED) {
                throw new ResponseExpiredException();
            }
            return toCachedResponse(refresh, page.sequence(), response);
        } finally {
            releaseQuietly(query, ownerLock);
        }
    }

    private CachedAttachmentResponse toCachedResponse(
            AttachmentReadRefresh refresh,
            long generation,
            TripAttachmentListResponse response
    ) {
        long lifetime = TimeUnit.MILLISECONDS.toNanos(refresh.expiresAtEpochMillis() - refresh.startedAtEpochMillis());
        return new CachedAttachmentResponse(response, refresh.startedAtNanos() + lifetime,
                refresh.refreshId(), generation);
    }

    private Optional<CachedAttachmentResponse> usableCachedResponse(AttachmentPageKey query) {
        return responseCache.findResponse(query).filter((CachedAttachmentResponse cached) ->
                cached.isUsableAt(nanoTime.getAsLong()));
    }

    private Flight joinOrCreate(AttachmentPageKey query) {
        Flight proposed = new Flight();
        Flight existing = pending.putIfAbsent(query, proposed);
        return existing == null ? proposed : existing;
    }

    private CachedAttachmentResponse awaitLocal(Flight flight, AttachmentReadContext context) {
        context.requireWaitBudget();
        try {
            return flight.result.get(context.remainingWaitNanos(), TimeUnit.NANOSECONDS);
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

    private CachedAttachmentResponse requireUsableResponse(CachedAttachmentResponse cached, AttachmentReadContext context) {
        context.requireWaitBudget();
        if (!cached.isUsableAt(nanoTime.getAsLong())) {
            throw new ResponseExpiredException();
        }
        return cached;
    }

    private void requireNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(TripInternalErrorMessage.PHOTO_LIST_RESPONSE_TRANSACTION_ACTIVE.message());
        }
    }

    private void retireFlight(AttachmentPageKey query, Flight flight) {
        pending.remove(query, flight);
        flight.result.completeExceptionally(new LocalFlightRetiredException());
    }

    private void releaseQuietly(AttachmentPageKey query, RLock ownerLock) {
        try {
            locks.release(query.tripId(), responseCache.responseLockKey(query), ownerLock);
        } catch (RuntimeException exception) {
            log.atWarn().addKeyValue("event", "photo_final_response_release_failure")
                    .addKeyValue("trip_id", query.tripId())
                    .addKeyValue("failure", exception.getClass().getSimpleName())
                    .log(TripInternalErrorMessage.PHOTO_LIST_RESPONSE_LOCK_RELEASE_FAILED.message());
        }
    }

    private void emitReadEvent(Phase phase, Outcome outcome, long startedAtNanos, String refreshId) {
        try {
            events.publishEvent(new TripAttachmentReadEvent(phase, outcome,
                    nanoTime.getAsLong() - startedAtNanos, pending.size(), refreshId));
        } catch (RuntimeException exception) {
            log.atWarn().addKeyValue("event", "photo_final_response_observation_failure")
                    .addKeyValue("failure", exception.getClass().getSimpleName())
                    .log(TripInternalErrorMessage.PHOTO_LIST_RESPONSE_OBSERVATION_FAILED.message());
        }
    }

    private AttachmentReadRejectedException rejected(AttachmentReadRejectedException.Reason reason) {
        return new AttachmentReadRejectedException(reason);
    }

    private static final class Flight {

        private final CompletableFuture<CachedAttachmentResponse> result = new CompletableFuture<>();
        private final AtomicBoolean claimed = new AtomicBoolean();
    }

    private static final class LocalFlightRetiredException extends RuntimeException {

        private LocalFlightRetiredException() {
            super(TripInternalErrorMessage.PHOTO_LIST_RESPONSE_LEADER_WAIT_EXPIRED.message());
        }
    }

    private static final class ResponseExpiredException extends RuntimeException {

        private ResponseExpiredException() {
            super(TripInternalErrorMessage.PHOTO_LIST_RESPONSE_LIFETIME_EXPIRED.message());
        }
    }
}

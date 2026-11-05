package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import org.redisson.api.RLock;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public record AttachmentReadRefresh(
        String valueKey,
        String lockKey,
        RLock ownerLock,
        String refreshId,
        long startedAtNanos,
        long startedAtEpochMillis,
        long expiresAtEpochMillis
) {

    public AttachmentReadRefresh {
        Objects.requireNonNull(valueKey, TripInternalErrorMessage.PHOTO_LIST_QUERY_MISSING.message());
        Objects.requireNonNull(lockKey, TripInternalErrorMessage.PHOTO_LIST_QUERY_MISSING.message());
        Objects.requireNonNull(ownerLock, TripInternalErrorMessage.PHOTO_LIST_RESPONSE_OWNER_LOCK_MISSING.message());
        Objects.requireNonNull(refreshId, TripInternalErrorMessage.PHOTO_LIST_RESPONSE_REFRESH_ID_MISSING.message());
    }

    public long remainingLifetimeNanos(long nowNanos) {
        return TimeUnit.MILLISECONDS.toNanos(500L) - (nowNanos - startedAtNanos);
    }
}

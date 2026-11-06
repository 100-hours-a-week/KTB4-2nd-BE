package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.common.exception.AttachmentReadRejectedException;
import com.yeodam.yeodambe.trip.config.TripAttachmentCacheProperties;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class TripAttachmentRedisLockClient {

    private final RedissonClient redisson;
    private final TripAttachmentCacheProperties properties;

    public TripAttachmentRedisLockClient(
            @Lazy @Qualifier("photoListRedissonClient") RedissonClient redisson,
            TripAttachmentCacheProperties properties
    ) {
        this.redisson = redisson;
        this.properties = properties;
    }

    public Optional<RLock> tryAcquire(Long tripId, String lockKey, long waitNanos) {
        try {
            RLock lock = redisson.getLock(lockKey);
            boolean acquired = lock.tryLock(Math.max(0L, waitNanos),
                    properties.getReadLockLease().toNanos(), TimeUnit.NANOSECONDS);
            return acquired ? Optional.of(lock) : Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AttachmentReadRejectedException(AttachmentReadRejectedException.Reason.INTERRUPTED);
        } catch (RuntimeException exception) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AttachmentReadRejectedException(AttachmentReadRejectedException.Reason.INTERRUPTED);
            }
            throw lockUnavailable("acquire", tripId, exception);
        }
    }

    public boolean release(Long tripId, String lockKey, RLock ownerLock) {
        try {
            if (!lockKey.equals(ownerLock.getName()) || !ownerLock.isHeldByCurrentThread()) {
                return false;
            }
            ownerLock.unlock();
            return true;
        } catch (IllegalMonitorStateException exception) {
            return false;
        } catch (RuntimeException exception) {
            throw lockUnavailable("release", tripId, exception);
        }
    }

    private AttachmentReadRejectedException lockUnavailable(
            String operation,
            Long tripId,
            RuntimeException cause
    ) {
        log.atWarn().addKeyValue("event", "photo_final_response_store_failure")
                .addKeyValue("operation", operation).addKeyValue("trip_id", tripId)
                .addKeyValue("failure", cause.getClass().getSimpleName())
                .log(TripInternalErrorMessage.PHOTO_LIST_RESPONSE_STORE_UNAVAILABLE.message());
        AttachmentReadRejectedException failure = new AttachmentReadRejectedException(
                AttachmentReadRejectedException.Reason.STORE_UNAVAILABLE);
        failure.initCause(cause);
        return failure;
    }
}

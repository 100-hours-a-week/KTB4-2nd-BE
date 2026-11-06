package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@Slf4j
public class TripEditLock {
    private final RedissonClient redisson;
    private final TripEditLockProperties properties;
    private final MeterRegistry meters;

    public TripEditLock(
            @Lazy @Qualifier("tripEditRedissonClient") RedissonClient redisson,
            TripEditLockProperties properties,
            MeterRegistry meters
    ) {
        this.redisson = redisson;
        this.properties = properties;
        this.meters = meters;
    }

    public <T> T execute(Long tripId, Supplier<T> operation) {
        if (!properties.enabled()) {
            return operation.get();
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(TripInternalErrorMessage.EDIT_LOCK_REQUIRES_NEW_TRANSACTION.message());
        }
        RLock lock = acquire(tripId);
        Timer.Sample held = Timer.start(meters);
        String outcome = "failed";
        try {
            T result = operation.get();
            outcome = "completed";
            return result;
        } finally {
            release(lock);
            held.stop(Timer.builder("yeodam.trip.edit.lock.hold")
                    .tag("outcome", outcome).register(meters));
        }
    }

    private RLock acquire(Long tripId) {
        Timer.Sample wait = Timer.start(meters);
        String outcome = "unavailable";
        try {
            RLock lock = redisson.getLock(properties.namespace() + ":lock:trip:mutation:" + tripId);
            boolean acquired = lock.tryLock(properties.waitTime().toMillis(), TimeUnit.MILLISECONDS);
            outcome = acquired ? "acquired" : "contended";
            if (!acquired) {
                throw new TripEditLockRejectedException(TripEditLockRejectedException.Reason.CONTENDED);
            }
            return lock;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
            throw new TripEditLockRejectedException(TripEditLockRejectedException.Reason.INTERRUPTED);
        } catch (RedisException | BeanCreationException exception) {
            throw new TripEditLockRejectedException(TripEditLockRejectedException.Reason.STORE_UNAVAILABLE);
        } finally {
            wait.stop(Timer.builder("yeodam.trip.edit.lock.wait")
                    .tag("outcome", outcome).register(meters));
        }
    }

    private void release(RLock lock) {
        String outcome = "released";
        try {
            lock.unlock();
        } catch (IllegalMonitorStateException exception) {
            outcome = "ownership_lost";
            log.warn("여행 편집 락 해제 시 소유권이 이미 상실되었습니다.");
        } catch (RuntimeException exception) {
            outcome = "failed";
            log.warn("여행 편집 락 해제 실패: failure={}", exception.getClass().getSimpleName());
        } finally {
            meters.counter("yeodam.trip.edit.lock.release", "outcome", outcome).increment();
        }
    }
}

package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripEditLockTest {
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock redisLock = mock(RLock.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void resetThreadState() {
        Thread.interrupted();
        TransactionSynchronizationManager.clear();
        meters.close();
    }

    @Test
    void 기본_설정은_락_획득을_2초_대기한다() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of()));
        TripEditLockProperties properties = binder.bindOrCreate("app.trip.edit-lock", TripEditLockProperties.class);

        assertThat(properties.waitTime()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.watchdogTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void 락이_비활성화되면_Redis에_접근하지_않는다() {
        when(redisson.getLock(anyString())).thenThrow(new AssertionError(TripEditLockTestErrorMessage.REDIS_ACCESS_FORBIDDEN.message()));
        assertThat(lock(false).execute(7L, () -> "saved")).isEqualTo("saved");
    }

    @Test
    void 락은_작업_전체가_끝난_후_해제된다() throws Exception {
        List<String> events = new ArrayList<>();
        when(redisson.getLock("test:lock:trip:mutation:7")).thenReturn(redisLock);
        when(redisLock.tryLock(500, TimeUnit.MILLISECONDS)).thenAnswer(invocation -> {
            events.add("acquired");
            return true;
        });
        doAnswer(invocation -> {
            events.add("released");
            return null;
        }).when(redisLock).unlock();

        String result = lock(true).execute(7L, () -> {
            events.add("committed");
            return "saved";
        });

        assertThat(result).isEqualTo("saved");
        assertThat(events).containsExactly("acquired", "committed", "released");
        assertThat(meters.get("yeodam.trip.edit.lock.wait").tag("outcome", "acquired").timer().count()).isEqualTo(1);
    }

    @Test
    void 사전_소유권_조회가_실패해도_락을_해제한다() throws Exception {
        acquired();
        when(redisLock.isHeldByCurrentThread()).thenThrow(
                new RedisConnectionException(TripEditLockTestErrorMessage.SENSITIVE_RELEASE_FAILURE.message()));
        AtomicBoolean released = new AtomicBoolean();
        doAnswer(invocation -> {
            released.set(true);
            return null;
        }).when(redisLock).unlock();

        assertThat(lock(true).execute(7L, () -> "committed")).isEqualTo("committed");
        assertThat(released).isTrue();
        assertThat(meters.get("yeodam.trip.edit.lock.release").tag("outcome", "released").counter().count()).isEqualTo(1);
    }

    @Test
    void 락_경합이면_업무를_실행하지_않는다() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(redisLock);
        when(redisLock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);
        AtomicBoolean mutated = new AtomicBoolean();

        assertThatThrownBy(() -> lock(true).execute(7L, () -> {
            mutated.set(true);
            return "wrong";
        })).isInstanceOfSatisfying(TripEditLockRejectedException.class,
                error -> assertThat(error.reason()).isEqualTo(TripEditLockRejectedException.Reason.CONTENDED));
        assertThat(mutated).isFalse();
    }

    @Test
    void Redis_장애이면_업무를_실행하거나_민감한_메시지를_노출하지_않는다() {
        when(redisson.getLock(anyString())).thenThrow(new RedisConnectionException(TripEditLockTestErrorMessage.SENSITIVE_REDIS_CONNECTION.message()));
        assertUnavailableWithoutWork();
    }

    @Test
    void 지연_클라이언트_초기화_실패도_사용_불가로_처리한다() {
        when(redisson.getLock(anyString())).thenThrow(new BeanCreationException(TripEditLockTestErrorMessage.SENSITIVE_CLIENT_INITIALIZATION.message()));
        assertUnavailableWithoutWork();
    }

    @Test
    void 락_획득_중단은_인터럽트를_복구하고_업무를_실행하지_않는다() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(redisLock);
        when(redisLock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new InterruptedException(TripEditLockTestErrorMessage.INTERRUPTED.message()));

        assertThatThrownBy(() -> lock(true).execute(7L, () -> {
            throw new AssertionError(TripEditLockTestErrorMessage.BUSINESS_EXECUTION_FORBIDDEN.message());
        })).isInstanceOfSatisfying(TripEditLockRejectedException.class,
                error -> assertThat(error.reason()).isEqualTo(TripEditLockRejectedException.Reason.INTERRUPTED));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void 락_해제_실패는_커밋_성공을_덮지_않는다() throws Exception {
        acquired();
        doThrow(new RedisConnectionException(TripEditLockTestErrorMessage.SENSITIVE_RELEASE_FAILURE.message())).when(redisLock).unlock();
        assertThat(lock(true).execute(7L, () -> "committed")).isEqualTo("committed");
        assertThat(meters.get("yeodam.trip.edit.lock.release").tag("outcome", "failed").counter().count()).isEqualTo(1);
    }

    @Test
    void 락_소유권_상실은_커밋_성공을_덮지_않는다() throws Exception {
        acquired();
        doThrow(new IllegalMonitorStateException(TripEditLockTestErrorMessage.OWNERSHIP_LOST.message()))
                .when(redisLock).unlock();
        assertThat(lock(true).execute(7L, () -> "committed")).isEqualTo("committed");
        assertThat(meters.get("yeodam.trip.edit.lock.release").tag("outcome", "ownership_lost").counter().count()).isEqualTo(1);
    }

    @Test
    void 락_해제_실패는_원래_업무_예외를_덮지_않는다() throws Exception {
        acquired();
        doThrow(new RedisConnectionException(TripEditLockTestErrorMessage.SENSITIVE_RELEASE_FAILURE.message())).when(redisLock).unlock();
        IllegalArgumentException original = new IllegalArgumentException(TripEditLockTestErrorMessage.BUSINESS_ROLLBACK.message());
        assertThatThrownBy(() -> lock(true).execute(7L, () -> {
            throw original;
        })).isSameAs(original);
    }

    @Test
    void 활성_트랜잭션_안에서는_락을_획득할_수_없다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(redisson.getLock(anyString())).thenThrow(new AssertionError(TripEditLockTestErrorMessage.REJECT_BEFORE_REDIS.message()));
        assertThatThrownBy(() -> lock(true).execute(7L, () -> "wrong"))
                .isInstanceOf(IllegalStateException.class);
    }

    private TripEditLock lock(boolean enabled) {
        return new TripEditLock(redisson,
                new TripEditLockProperties(enabled, "test", Duration.ofMillis(500), Duration.ofSeconds(30)),
                meters);
    }

    private void acquired() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(redisLock);
        when(redisLock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
    }

    private void assertUnavailableWithoutWork() {
        assertThatThrownBy(() -> lock(true).execute(7L, () -> {
            throw new AssertionError(TripEditLockTestErrorMessage.BUSINESS_EXECUTION_FORBIDDEN.message());
        })).isInstanceOfSatisfying(TripEditLockRejectedException.class, error -> {
            assertThat(error.reason()).isEqualTo(TripEditLockRejectedException.Reason.STORE_UNAVAILABLE);
            assertThat(error.getMessage()).doesNotContain("secret", "redis://");
            assertThat(error.getCause()).isNull();
        });
    }
}

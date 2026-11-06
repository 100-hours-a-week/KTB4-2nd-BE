package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.common.exception.AttachmentReadRejectedException;
import com.yeodam.yeodambe.trip.config.TripAttachmentCacheProperties;
import com.yeodam.yeodambe.trip.fixture.TripAttachmentCacheErrorMessage;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripAttachmentRedisLockClientTest {

    private final RedissonClient redisson = mock(RedissonClient.class);
    private final TripAttachmentCacheProperties properties = new TripAttachmentCacheProperties();
    private final TripAttachmentRedisLockClient client = new TripAttachmentRedisLockClient(redisson, properties);
    private final RLock lock = mock(RLock.class);

    @Test
    void 다른_정규_키의_잠금은_해제하지_않는다() {
        when(lock.getName()).thenReturn("another-trip:lock");
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThat(client.release(2L, "expected-trip:lock", lock)).isFalse();

        verify(lock, never()).unlock();
    }

    @Test
    void Redis_잠금_획득_실패는_원래_원인을_보존한다() {
        RuntimeException failure = new IllegalStateException(TripAttachmentCacheErrorMessage.STORE_UNAVAILABLE.message());
        when(redisson.getLock("trip:lock")).thenThrow(failure);

        assertThatThrownBy(() -> client.tryAcquire(2L, "trip:lock", 0L))
                .isInstanceOfSatisfying(AttachmentReadRejectedException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(AttachmentReadRejectedException.Reason.STORE_UNAVAILABLE);
                    assertThat(exception.getCause()).isSameAs(failure);
                });
    }

    @Test
    void Redis_잠금_해제_실패는_원래_원인을_보존한다() {
        RuntimeException failure = new IllegalStateException(TripAttachmentCacheErrorMessage.CLEANUP_FAILED.message());
        when(lock.getName()).thenReturn("trip:lock");
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(failure).when(lock).unlock();

        assertThatThrownBy(() -> client.release(2L, "trip:lock", lock))
                .isInstanceOfSatisfying(AttachmentReadRejectedException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(AttachmentReadRejectedException.Reason.STORE_UNAVAILABLE);
                    assertThat(exception.getCause()).isSameAs(failure);
                });
    }

    @Test
    void 획득_중_인터럽트가_발생하면_인터럽트_플래그를_복원한다() throws InterruptedException {
        when(redisson.getLock("trip:lock")).thenReturn(lock);
        when(lock.tryLock(0L, properties.getReadLockLease().toNanos(), TimeUnit.NANOSECONDS))
                .thenThrow(new InterruptedException());

        try {
            assertThatThrownBy(() -> client.tryAcquire(2L, "trip:lock", 0L))
                    .isInstanceOfSatisfying(AttachmentReadRejectedException.class, exception ->
                            assertThat(exception.reason()).isEqualTo(AttachmentReadRejectedException.Reason.INTERRUPTED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}

package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentReadRejectedException;
import com.yeodam.yeodambe.common.exception.PlaceFolderNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentRedisLockClient;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository.PhotoReadState;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.response.AttachmentReadRefresh;
import com.yeodam.yeodambe.trip.service.response.CachedTripReadAccessSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripReadAccessSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripReadPermit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.redisson.api.RLock;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TripAttachmentReadAccessServiceTest {

    private static final Instant BASE = Instant.parse("2026-11-05T03:00:00Z");
    private final AtomicLong nano = new AtomicLong();
    private final Clock wall = mock(Clock.class);
    private final TripRepository trips = mock(TripRepository.class);
    private final TripShareUserRepository shares = mock(TripShareUserRepository.class);
    private final TripAttachmentRedisCacheStore store = mock(TripAttachmentRedisCacheStore.class);
    private final TripAttachmentRedisLockClient locks = mock(TripAttachmentRedisLockClient.class);
    private final RLock lock = mock(RLock.class);
    private final AtomicReference<CachedTripReadAccessSnapshot> cached = new AtomicReference<>();
    private TripAttachmentReadAccessService service;

    @BeforeEach
    void prepare() {
        when(store.accessLockKey(7L)).thenReturn("access-lock");
        when(wall.instant()).thenAnswer(invocation -> BASE.plusNanos(nano.get()));
        PhotoReadState state = mock(PhotoReadState.class);
        when(state.getTripId()).thenReturn(7L);
        when(state.getOwnerId()).thenReturn(1L);
        when(trips.findPhotoReadStateById(7L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return Optional.of(state);
        });
        List<TripShareUserRepository.ReadGrantRow> grants = List.of(
                grant(2L, TripSharePermission.READ, BASE.plusSeconds(100)),
                grant(3L, TripSharePermission.WRITE, BASE.plusSeconds(100)));
        when(shares.findReadableGrantsByTripIdAndOwnerId(eq(7L), eq(1L), any())).thenReturn(grants);
        when(store.findAccess(7L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.ofNullable(cached.get()).filter(value -> value.isUsableAt(nano.get()));
        });
        when(locks.tryAcquire(eq(7L), eq("access-lock"), anyLong())).thenReturn(Optional.of(lock));
        when(store.beginAccessRefresh(7L, lock)).thenAnswer(invocation -> new AttachmentReadRefresh(
                "value", "lock", lock, "access", nano.get(), 0L, 495L));
        when(store.publishAccess(eq(7L), any(), any())).thenAnswer(invocation -> {
            AttachmentReadRefresh refresh = invocation.getArgument(1);
            cached.set(new CachedTripReadAccessSnapshot(invocation.getArgument(2),
                    refresh.startedAtNanos() + 495_000_000L, refresh.refreshId()));
            return TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED;
        });
        service = new TripAttachmentReadAccessService(trips, shares, store, locks, new TestTransactions(), wall, nano::get);
    }

    @Test
    void 서로_다른_회원이_하나의_미적재_여행_스냅샷을_공유한다() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            loading.countDown();
            assertThat(finish.await(3, TimeUnit.SECONDS)).isTrue();
            return List.of(grant(2L, TripSharePermission.READ, BASE.plusSeconds(100)),
                    grant(3L, TripSharePermission.WRITE, BASE.plusSeconds(100)));
        }).when(shares).findReadableGrantsByTripIdAndOwnerId(eq(7L), eq(1L), any());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = List.of(1L, 2L, 3L, 4L).stream().map(user -> executor.submit(() -> {
                entered.countDown();
                try {
                    return service.requireReadable(user, 7L, context()).userId().equals(user);
                } catch (PlaceFolderNotFoundException exception) {
                    return false;
                }
            })).toList();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(loading.await(3, TimeUnit.SECONDS)).isTrue();
            finish.countDown();
            assertThat(results.get(0).get(3, TimeUnit.SECONDS)).isTrue();
            assertThat(results.get(1).get(3, TimeUnit.SECONDS)).isTrue();
            assertThat(results.get(2).get(3, TimeUnit.SECONDS)).isTrue();
            assertThat(results.get(3).get(3, TimeUnit.SECONDS)).isFalse();
        } finally {
            finish.countDown();
        }
        verify(shares, times(1)).findReadableGrantsByTripIdAndOwnerId(eq(7L), eq(1L), any());
        verify(shares, never()).findByTripIdAndGranteeUserId(anyLong(), anyLong());
    }

    @Test
    void 확인된_만료는_해당_회원만_거부한다() {
        cached.set(new CachedTripReadAccessSnapshot(new TripReadAccessSnapshot(7L, 1L, List.of(
                new TripReadAccessSnapshot.Member(2L, TripSharePermission.READ, BASE.plusMillis(100)),
                new TripReadAccessSnapshot.Member(3L, TripSharePermission.WRITE, BASE.plusSeconds(100)))), 495_000_000L, "a"));
        TripReadPermit permit = service.requireReadable(2L, 7L, context());
        assertThat(permit.validUntilNanos()).isEqualTo(100_000_000L);
        nano.set(100_000_000L);
        assertThatThrownBy(() -> service.revalidate(permit, context())).isInstanceOf(PlaceFolderNotFoundException.class);
        assertThat(service.requireReadable(3L, 7L, context()).userId()).isEqualTo(3L);
        verifyNoInteractions(trips, shares);
    }

    @Test
    void 새_권한은_사용자별_우회_없이_스냅샷_교체를_기다린다() {
        cached.set(new CachedTripReadAccessSnapshot(new TripReadAccessSnapshot(7L, 1L, List.of()), 495_000_000L, "old"));
        assertThatThrownBy(() -> service.requireReadable(2L, 7L, context())).isInstanceOf(PlaceFolderNotFoundException.class);
        verifyNoInteractions(trips, shares);
        nano.set(500_000_000L);
        assertThat(service.requireReadable(2L, 7L, context()).userId()).isEqualTo(2L);
    }

    @Test
    void 철회된_권한은_원래_마감_후_다시_평가한다() {
        AttachmentReadContext context = context();
        TripReadPermit permit = service.requireReadable(2L, 7L, context);
        when(shares.findReadableGrantsByTripIdAndOwnerId(eq(7L), eq(1L), any())).thenReturn(List.of());
        nano.set(500_000_000L);
        assertThatThrownBy(() -> service.revalidate(permit, context)).isInstanceOf(PlaceFolderNotFoundException.class);
    }

    @Test
    void 권한_저장소_실패시_DB로_우회하지_않는다() {
        when(store.findAccess(7L)).thenThrow(new AttachmentReadRejectedException(
                AttachmentReadRejectedException.Reason.STORE_UNAVAILABLE));
        assertThatThrownBy(() -> service.requireReadable(2L, 7L, context()))
                .isInstanceOf(AttachmentReadRejectedException.class);
        verifyNoInteractions(trips, shares);
    }

    @Test
    void 두_번째_스냅샷도_만료되면_세_번째를_시작하지_않는다() {
        when(store.publishAccess(eq(7L), any(), any())).thenReturn(TripAttachmentRedisCacheStore.PublishOutcome.EXPIRED);
        assertThatThrownBy(() -> service.requireReadable(2L, 7L, context()))
                .isInstanceOf(AttachmentReadRejectedException.class)
                .extracting("reason").isEqualTo(AttachmentReadRejectedException.Reason.RETRY_EXHAUSTED);
        verify(trips, times(2)).findPhotoReadStateById(7L);
    }

    @Test
    void 선행_요청의_갱신_한도_초과가_새_후속_요청을_실패시키지_않는다() throws Exception {
        CountDownLatch thirdAcquire = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger acquisitions = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        AtomicReference<Thread> followerThread = new AtomicReference<>();
        when(locks.tryAcquire(eq(7L), eq("access-lock"), anyLong())).thenAnswer(invocation -> {
            if (acquisitions.incrementAndGet() == 3) {
                thirdAcquire.countDown();
                assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            }
            return Optional.of(lock);
        });
        when(store.publishAccess(eq(7L), any(), any())).thenAnswer(invocation -> {
            if (publications.incrementAndGet() <= 2) {
                nano.addAndGet(500_000_000L);
                return TripAttachmentRedisCacheStore.PublishOutcome.EXPIRED;
            }
            AttachmentReadRefresh refresh = invocation.getArgument(1);
            cached.set(new CachedTripReadAccessSnapshot(invocation.getArgument(2),
                    refresh.startedAtNanos() + 495_000_000L, refresh.refreshId()));
            return TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED;
        });
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TripReadPermit> leader = executor.submit(() -> service.requireReadable(2L, 7L, context()));
            try {
                assertThat(thirdAcquire.await(3, TimeUnit.SECONDS)).isTrue();
                Future<TripReadPermit> follower = executor.submit(() -> {
                    followerThread.set(Thread.currentThread());
                    return service.requireReadable(3L, 7L, context());
                });
                awaitSnapshotFollower(followerThread);
                release.countDown();
                assertThatThrownBy(() -> leader.get(3, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(AttachmentReadRejectedException.class)
                        .satisfies(failure -> assertThat(((AttachmentReadRejectedException) failure.getCause()).reason())
                                .isEqualTo(AttachmentReadRejectedException.Reason.RETRY_EXHAUSTED));
                assertThat(follower.get(3, TimeUnit.SECONDS).userId()).isEqualTo(3L);
            } finally {
                release.countDown();
            }
        }
        assertThat(publications).hasValue(3);
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(service, "pending")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"0,0,SUCCESS", "2,0,RETRY_EXHAUSTED", "0,2000,TIMEOUT"})
    void 선행_요청의_인터럽트가_후속_요청의_대기_예산과_시도_횟수를_보존한다(int usedAttempts, long elapsedMillis, String expected)
            throws Exception {
        CountDownLatch acquiring = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger acquisitions = new AtomicInteger();
        AtomicReference<Thread> leaderThread = new AtomicReference<>();
        AtomicReference<Thread> followerThread = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        when(locks.tryAcquire(eq(7L), eq("access-lock"), anyLong())).thenAnswer(invocation -> {
            if (acquisitions.incrementAndGet() == 1) {
                acquiring.countDown();
                try {
                    assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AttachmentReadRejectedException(AttachmentReadRejectedException.Reason.INTERRUPTED);
                }
            }
            return Optional.of(lock);
        });
        AttachmentReadContext followerContext = context();
        followerContext.onCacheMiss();
        for (int attempt = 0; attempt < usedAttempts; attempt++) {
            followerContext.beginAccessRefresh();
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AttachmentReadRejectedException.Reason> leader = executor.submit(() -> {
                leaderThread.set(Thread.currentThread());
                try {
                    service.requireReadable(2L, 7L, context());
                    return null;
                } catch (AttachmentReadRejectedException exception) {
                    interrupted.set(Thread.currentThread().isInterrupted());
                    return exception.reason();
                }
            });
            try {
                assertThat(acquiring.await(3, TimeUnit.SECONDS)).isTrue();
                Future<TripReadPermit> follower = executor.submit(() -> {
                    followerThread.set(Thread.currentThread());
                    return service.requireReadable(3L, 7L, followerContext);
                });
                awaitSnapshotFollower(followerThread);
                nano.set(TimeUnit.MILLISECONDS.toNanos(elapsedMillis));
                leaderThread.get().interrupt();
                assertThat(leader.get(3, TimeUnit.SECONDS)).isEqualTo(AttachmentReadRejectedException.Reason.INTERRUPTED);
                assertThat(interrupted).isTrue();
                if (expected.equals("SUCCESS")) {
                    assertThat(follower.get(3, TimeUnit.SECONDS).userId()).isEqualTo(3L);
                    verify(trips).findPhotoReadStateById(7L);
                } else {
                    assertThatThrownBy(() -> follower.get(3, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(AttachmentReadRejectedException.class)
                            .satisfies(failure -> assertThat(((AttachmentReadRejectedException) failure.getCause()).reason().name())
                                    .isEqualTo(expected));
                    verifyNoInteractions(trips, shares);
                }
            } finally {
                release.countDown();
            }
        }
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(service, "pending")).isEmpty();
    }

    private void awaitSnapshotFollower(AtomicReference<Thread> thread) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(thread.get()).isNotNull();
            assertThat(Arrays.stream(thread.get().getStackTrace()).anyMatch(frame ->
                    frame.getClassName().equals("java.util.concurrent.CompletableFuture")
                            && frame.getMethodName().equals("timedGet"))).isTrue();
        });
    }

    @Test
    void 선행_요청_시간_초과가_늦게_온_후속_요청을_취소하지_않는다() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch following = new CountDownLatch(1);
        when(locks.tryAcquire(eq(7L), eq("access-lock"), anyLong())).thenAnswer(invocation -> {
            nano.set(1_900_000_000L);
            return Optional.of(lock);
        });
        doAnswer(invocation -> {
            loading.countDown();
            assertThat(finish.await(3, TimeUnit.SECONDS)).isTrue();
            return List.of(grant(2L, TripSharePermission.READ, BASE.plusSeconds(100)));
        }).when(shares).findReadableGrantsByTripIdAndOwnerId(eq(7L), eq(1L), any());
        when(store.findAccess(7L)).thenAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("access-follower")) {
                following.countDown();
            }
            return Optional.ofNullable(cached.get()).filter(value -> value.isUsableAt(nano.get()));
        });
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TripReadPermit> leader = executor.submit(() -> service.requireReadable(2L, 7L, context()));
            try {
                assertThat(loading.await(3, TimeUnit.SECONDS)).isTrue();
                nano.set(1_950_000_000L);
                Future<TripReadPermit> follower = executor.submit(() -> {
                    Thread.currentThread().setName("access-follower");
                    return service.requireReadable(2L, 7L, context());
                });
                assertThat(following.await(3, TimeUnit.SECONDS)).isTrue();
                nano.set(2_100_000_000L);
                finish.countDown();
                assertThatThrownBy(() -> leader.get(3, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(AttachmentReadRejectedException.class);
                assertThat(follower.get(3, TimeUnit.SECONDS).userId()).isEqualTo(2L);
            } finally {
                finish.countDown();
            }
        }
    }

    @Test
    void 권한_조회_후속_요청의_인터럽트가_선행_요청을_취소하지_않는다() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            loading.countDown();
            assertThat(finish.await(3, TimeUnit.SECONDS)).isTrue();
            return List.of(grant(2L, TripSharePermission.READ, BASE.plusSeconds(100)));
        }).when(shares).findReadableGrantsByTripIdAndOwnerId(eq(7L), eq(1L), any());
        AtomicReference<AttachmentReadRejectedException.Reason> reason = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TripReadPermit> leader = executor.submit(() -> service.requireReadable(2L, 7L, context()));
            try {
                assertThat(loading.await(3, TimeUnit.SECONDS)).isTrue();
                Thread follower = Thread.ofVirtual().start(() -> {
                    Thread.currentThread().interrupt();
                    try {
                        service.requireReadable(2L, 7L, context());
                    } catch (AttachmentReadRejectedException exception) {
                        reason.set(exception.reason());
                        interrupted.set(Thread.currentThread().isInterrupted());
                    }
                });
                assertThat(follower.join(Duration.ofSeconds(3))).isTrue();
                assertThat(reason.get()).isEqualTo(AttachmentReadRejectedException.Reason.INTERRUPTED);
                assertThat(interrupted).isTrue();
                finish.countDown();
                assertThat(leader.get(3, TimeUnit.SECONDS).userId()).isEqualTo(2L);
            } finally {
                finish.countDown();
            }
        }
    }

    @Test
    void 시계_읽기_지연이_소유자_권한의_유효기간을_늘리지_않는다() {
        TripReadPermit original = new TripReadPermit(1L, 7L, "old", 495_000_000L, null);
        when(wall.instant()).thenAnswer(invocation -> {
            nano.set(600_000_000L);
            return BASE.plusMillis(600);
        });
        TripReadPermit renewed = service.revalidate(original, context());
        assertThat(renewed.snapshotId()).isNotEqualTo("old");
        assertThat(renewed.validUntilNanos()).isGreaterThan(nano.get());
    }

    @Test
    void 권한_없는_요청이_권한_있는_진행중_요청을_실패시키지_않는다() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch following = new CountDownLatch(1);
        doAnswer(invocation -> {
            loading.countDown();
            assertThat(finish.await(3, TimeUnit.SECONDS)).isTrue();
            return List.of(grant(2L, TripSharePermission.READ, BASE.plusSeconds(100)));
        }).when(shares).findReadableGrantsByTripIdAndOwnerId(eq(7L), eq(1L), any());
        when(store.findAccess(7L)).thenAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("authorized-follower")) {
                following.countDown();
            }
            return Optional.ofNullable(cached.get()).filter(value -> value.isUsableAt(nano.get()));
        });
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TripReadPermit> unauthorized = executor.submit(() -> service.requireReadable(4L, 7L, context()));
            try {
                assertThat(loading.await(3, TimeUnit.SECONDS)).isTrue();
                Future<TripReadPermit> authorized = executor.submit(() -> {
                    Thread.currentThread().setName("authorized-follower");
                    return service.requireReadable(2L, 7L, context());
                });
                assertThat(following.await(3, TimeUnit.SECONDS)).isTrue();
                finish.countDown();
                assertThatThrownBy(() -> unauthorized.get(3, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(PlaceFolderNotFoundException.class);
                assertThat(authorized.get(3, TimeUnit.SECONDS).userId()).isEqualTo(2L);
            } finally {
                finish.countDown();
            }
        }
        verify(shares).findReadableGrantsByTripIdAndOwnerId(eq(7L), eq(1L), any());
    }

    @Test
    void 시계_읽기_지연이_확인된_공유_만료를_늦추지_않는다() {
        cached.set(new CachedTripReadAccessSnapshot(new TripReadAccessSnapshot(7L, 1L, List.of(
                new TripReadAccessSnapshot.Member(2L, TripSharePermission.READ, BASE.plusMillis(100)))), 495_000_000L, "old"));
        AtomicBoolean first = new AtomicBoolean(true);
        when(wall.instant()).thenAnswer(invocation -> {
            if (first.compareAndSet(true, false)) {
                nano.set(150_000_000L);
                return BASE;
            }
            return BASE.plusNanos(nano.get());
        });
        assertThatThrownBy(() -> service.requireReadable(2L, 7L, context()))
                .isInstanceOf(PlaceFolderNotFoundException.class);
    }

    private AttachmentReadContext context() {
        return new AttachmentReadContext(nano::get);
    }

    private TripShareUserRepository.ReadGrantRow grant(Long user, TripSharePermission permission, Instant expiresAt) {
        TripShareUserRepository.ReadGrantRow row = mock(TripShareUserRepository.ReadGrantRow.class);
        when(row.getGranteeUserId()).thenReturn(user);
        when(row.getPermission()).thenReturn(permission);
        when(row.getExpiresAt()).thenReturn(LocalDateTime.ofInstant(expiresAt, ZoneId.of("Asia/Seoul")));
        return row;
    }

    private static final class TestTransactions extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}

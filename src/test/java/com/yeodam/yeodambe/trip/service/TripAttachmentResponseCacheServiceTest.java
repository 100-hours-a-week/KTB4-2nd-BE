package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentReadRejectedException;
import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import com.yeodam.yeodambe.common.exception.PlaceFolderNotFoundException;
import com.yeodam.yeodambe.common.exception.TripDetailNotAvailableException;
import com.yeodam.yeodambe.trip.client.TripAttachmentRedisLockClient;
import com.yeodam.yeodambe.trip.fixture.TripAttachmentCacheErrorMessage;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageKey;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageQuery;
import com.yeodam.yeodambe.trip.service.response.AttachmentPageData;
import com.yeodam.yeodambe.trip.service.response.AttachmentPageReadScope;
import com.yeodam.yeodambe.trip.service.response.AttachmentReadRefresh;
import com.yeodam.yeodambe.trip.service.response.CachedAttachmentResponse;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentListResponse;
import com.yeodam.yeodambe.trip.service.response.TripReadPermit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.redisson.api.RLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TripAttachmentResponseCacheServiceTest {

    private final AttachmentPageQuery query = new AttachmentPageQuery(1L, 2L, 3L, null);
    private final AttachmentPageKey key = new AttachmentPageKey(2L, 3L, null);
    private final TripAttachmentReadAccessService access = mock(TripAttachmentReadAccessService.class);
    private final AttachmentPageData page = new AttachmentPageData(List.of(), false, null, 4L);
    private final TripAttachmentListResponse response = new TripAttachmentListResponse(List.of(), false, null, 4L);
    private final TripAttachmentPageReader reader = mock(TripAttachmentPageReader.class);
    private final TripAttachmentRedisCacheStore store = mock(TripAttachmentRedisCacheStore.class);
    private final TripAttachmentRedisLockClient locks = mock(TripAttachmentRedisLockClient.class);
    private final RLock ownerLock = mock(RLock.class);
    private final AtomicLong clock = new AtomicLong();
    private final AtomicInteger transactions = new AtomicInteger();
    private final Function<AttachmentPageData, TripAttachmentListResponse> mapper =
            (AttachmentPageData data) -> response;
    private ApplicationEventPublisher events = (Object event) -> {
    };

    @BeforeEach
    void prepare() {
        when(store.responseLockKey(any())).thenReturn("response-lock");
        when(access.requireReadable(anyLong(), anyLong(), any())).thenAnswer(invocation ->
                new TripReadPermit(invocation.getArgument(0), invocation.getArgument(1), "access", Long.MAX_VALUE, null));
        when(access.revalidate(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reader.normalizeKey(any())).thenAnswer(invocation -> {
            AttachmentPageQuery input = invocation.getArgument(0);
            return new AttachmentPageKey(input.tripId(), input.tripPlaceId(), input.cursor());
        });
        when(reader.readShared(key)).thenAnswer(invocation -> {
            TransactionTemplate transaction = new TransactionTemplate(new TestTransactions());
            transaction.setReadOnly(true);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return transaction.execute(status -> page);
        });
        when(store.findResponse(any())).thenReturn(Optional.empty());
        when(locks.tryAcquire(any(), eq("response-lock"), anyLong())).thenAnswer(invocation -> Optional.of(ownerLock));
        when(store.beginResponseRefresh(any(), any())).thenAnswer(invocation -> new AttachmentReadRefresh(
                "value", "lock", invocation.getArgument(1), "refresh", clock.get(), 1000L, 1495L));
        when(store.publishResponse(any(), any(), anyLong(), any())).thenReturn(
                TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
    }

    @Test
    void 캐시_적중이면_잠금과_DB조회와_매핑을_생략한다() {
        when(store.findResponse(key)).thenReturn(Optional.of(cached(100_000_000L)));
        assertThat(service().read(query, (AttachmentPageData data) -> {
            throw new AssertionError(TripAttachmentCacheErrorMessage.HIT_MUST_REUSE_RESPONSE.message());
        })).isSameAs(response);
        verify(locks, never()).tryAcquire(any(), eq("response-lock"), anyLong());
        verify(reader, never()).readShared(any());
        assertThat(transactions).hasValue(0);
    }

    @Test
    void 저장소_실패시_DB로_우회하지_않는다() {
        when(store.findResponse(key)).thenThrow(new AttachmentReadRejectedException(
                AttachmentReadRejectedException.Reason.STORE_UNAVAILABLE));

        assertRejected(() -> service().read(query, mapper),
                AttachmentReadRejectedException.Reason.STORE_UNAVAILABLE);

        verify(reader, never()).readShared(any());
        verify(reader, never()).read(any());
        assertThat(transactions).hasValue(0);
    }

    @Test
    void 성공_관측을_마친_뒤에도_적중_응답이_유효하다() {
        when(store.findResponse(key)).thenReturn(Optional.of(cached(100_000_000L)));
        delaySuccessfulReturnObservations(60_000_000L);

        assertThat(service().read(query, mapper)).isSameAs(response);

        assertThat(clock.get()).isLessThan(100_000_000L);
        verify(reader, never()).readShared(any());
    }

    @Test
    void 성공_관측을_마친_뒤에도_재생성_응답이_유효하다() {
        delaySuccessfulReturnObservations(300_000_000L);

        assertThat(service().read(query, mapper)).isSameAs(response);

        assertThat(clock.get()).isLessThan(495_000_000L);
        verify(reader).readShared(key);
    }

    @Test
    void 성공_관측_후에도_캐시_미스의_마감시각을_지킨다() {
        when(locks.tryAcquire(any(), eq("response-lock"), anyLong())).thenAnswer(invocation -> {
            clock.set(1_800_000_000L);
            return Optional.of(ownerLock);
        });
        delaySuccessfulReturnObservations(100_000_000L);

        assertThat(service().read(query, mapper)).isSameAs(response);

        assertThat(clock.get()).isLessThan(2_000_000_000L);
        verify(reader).readShared(key);
    }

    @Test
    void 이중_확인에서_적중하면_재생성을_생략한다() {
        when(store.findResponse(key)).thenReturn(Optional.empty()).thenReturn(Optional.empty())
                .thenReturn(Optional.of(cached(100_000_000L)));
        assertThat(service().read(query, mapper)).isSameAs(response);
        verify(locks).tryAcquire(any(), eq("response-lock"), anyLong());
        verify(store, never()).beginResponseRefresh(any(), any());
        verify(reader, never()).readShared(any());
    }

    @Test
    void 트랜잭션_종료_후_매퍼를_실행한다() {
        assertThat(service().read(query, (AttachmentPageData data) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return response;
        })).isSameAs(response);
        assertThat(transactions).hasValue(1);
        verify(store).publishResponse(any(), any(), anyLong(), any());
    }

    @Test
    void 갱신_성공시_분산_잠금을_반드시_해제한다() {
        assertThat(service().read(query, mapper)).isSameAs(response);

        verify(locks).release(any(), eq("response-lock"), any());
    }

    @Test
    void 동일한_로컬_캐시_미스는_매핑을_한_번만_수행한다() throws Exception {
        CountDownLatch mapping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        events = (Object observed) -> {
            TripAttachmentReadEvent event = (TripAttachmentReadEvent) observed;
            if (event.outcome() == TripAttachmentReadEvent.Outcome.FOLLOWER) {
                joined.countDown();
            }
        };
        TripAttachmentResponseCacheService service = service();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TripAttachmentListResponse> first = executor.submit(() -> service.read(query,
                    (AttachmentPageData data) -> {
                        calls.incrementAndGet();
                        mapping.countDown();
                        await(release);
                        return response;
                    }));
            try {
                await(mapping);
                Future<TripAttachmentListResponse> second = executor.submit(() -> service.read(new AttachmentPageQuery(9L, 2L, 3L, null), mapper));
                await(joined);
                release.countDown();
                assertThat(first.get(3, TimeUnit.SECONDS)).isSameAs(response);
                assertThat(second.get(3, TimeUnit.SECONDS)).isSameAs(response);
            } finally {
                release.countDown();
            }
        }
        assertThat(calls).hasValue(1);
        assertThat(transactions).hasValue(1);
    }

    @Test
    void 두_번째_생성도_만료되면_세_번째를_시작하지_않는다() {
        when(store.publishResponse(any(), any(), anyLong(), any())).thenReturn(
                TripAttachmentRedisCacheStore.PublishOutcome.EXPIRED);
        assertRejected(() -> service().read(query, mapper), AttachmentReadRejectedException.Reason.RETRY_EXHAUSTED);
        verify(store, times(2)).beginResponseRefresh(any(), any());
    }

    @Test
    void 선행_요청의_갱신_한도_초과가_새_후속_요청을_실패시키지_않는다() throws Exception {
        CountDownLatch thirdAcquire = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger acquisitions = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        when(locks.tryAcquire(any(), eq("response-lock"), anyLong())).thenAnswer(invocation -> {
            if (acquisitions.incrementAndGet() == 3) {
                thirdAcquire.countDown();
                await(release);
            }
            return Optional.of(ownerLock);
        });
        when(store.publishResponse(any(), any(), anyLong(), any())).thenAnswer(invocation -> {
            if (publications.incrementAndGet() <= 2) {
                clock.addAndGet(500_000_000L);
                return TripAttachmentRedisCacheStore.PublishOutcome.EXPIRED;
            }
            return TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED;
        });
        events = observed -> {
            TripAttachmentReadEvent event = (TripAttachmentReadEvent) observed;
            if (event.outcome() == TripAttachmentReadEvent.Outcome.FOLLOWER) {
                joined.countDown();
            }
        };
        TripAttachmentResponseCacheService service = service();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TripAttachmentListResponse> leader = executor.submit(() -> service.read(query, mapper));
            try {
                await(thirdAcquire);
                Future<TripAttachmentListResponse> follower = executor.submit(() -> service.read(
                        new AttachmentPageQuery(9L, 2L, 3L, null), mapper));
                await(joined);
                release.countDown();
                assertThatThrownBy(() -> leader.get(3, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(AttachmentReadRejectedException.class)
                        .satisfies(failure -> assertThat(((AttachmentReadRejectedException) failure.getCause()).reason())
                                .isEqualTo(AttachmentReadRejectedException.Reason.RETRY_EXHAUSTED));
                assertThat(follower.get(3, TimeUnit.SECONDS)).isSameAs(response);
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
        CountDownLatch joined = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> leaderThread = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger acquisitions = new AtomicInteger();
        when(access.requireReadable(eq(9L), anyLong(), any())).thenAnswer(invocation -> {
            AttachmentReadContext context = invocation.getArgument(2);
            context.onCacheMiss();
            for (int attempt = 0; attempt < usedAttempts; attempt++) {
                context.beginResponseRefresh();
            }
            return new TripReadPermit(9L, 2L, "access", Long.MAX_VALUE, null);
        });
        when(locks.tryAcquire(any(), eq("response-lock"), anyLong())).thenAnswer(invocation -> {
            if (acquisitions.incrementAndGet() == 1) {
                acquiring.countDown();
                try {
                    assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AttachmentReadRejectedException(AttachmentReadRejectedException.Reason.INTERRUPTED);
                }
            }
            return Optional.of(ownerLock);
        });
        events = observed -> {
            TripAttachmentReadEvent event = (TripAttachmentReadEvent) observed;
            if (event.outcome() == TripAttachmentReadEvent.Outcome.FOLLOWER) {
                joined.countDown();
            }
        };
        TripAttachmentResponseCacheService service = service();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AttachmentReadRejectedException.Reason> leader = executor.submit(() -> {
                leaderThread.set(Thread.currentThread());
                try {
                    service.read(query, mapper);
                    return null;
                } catch (AttachmentReadRejectedException exception) {
                    interrupted.set(Thread.currentThread().isInterrupted());
                    return exception.reason();
                }
            });
            try {
                await(acquiring);
                Future<TripAttachmentListResponse> follower = executor.submit(() -> service.read(
                        new AttachmentPageQuery(9L, 2L, 3L, null), mapper));
                await(joined);
                clock.set(TimeUnit.MILLISECONDS.toNanos(elapsedMillis));
                leaderThread.get().interrupt();
                assertThat(leader.get(3, TimeUnit.SECONDS)).isEqualTo(AttachmentReadRejectedException.Reason.INTERRUPTED);
                assertThat(interrupted).isTrue();
                if (expected.equals("SUCCESS")) {
                    assertThat(follower.get(3, TimeUnit.SECONDS)).isSameAs(response);
                    verify(reader).readShared(key);
                } else {
                    assertThatThrownBy(() -> follower.get(3, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(AttachmentReadRejectedException.class)
                            .satisfies(failure -> assertThat(((AttachmentReadRejectedException) failure.getCause()).reason().name())
                                    .isEqualTo(expected));
                    verify(reader, never()).readShared(any());
                }
            } finally {
                release.countDown();
            }
        }
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(service, "pending")).isEmpty();
    }

    @Test
    void 반환_전에_응답이_만료되면_재생성한다() {
        when(store.findResponse(key)).thenAnswer(invocation -> {
            clock.set(100_000_000L);
            return Optional.of(cached(100_000_000L));
        }).thenReturn(Optional.empty());
        assertThat(service().read(query, mapper)).isSameAs(response);
        verify(reader).readShared(key);
    }

    @Test
    void 선행_요청의_마감_초과가_새_후속_요청을_실패시키지_않는다() throws Exception {
        CountDownLatch mapping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);
        when(locks.tryAcquire(any(), eq("response-lock"), anyLong())).thenAnswer(invocation -> {
            clock.set(1_900_000_000L);
            return Optional.of(ownerLock);
        });
        events = (Object observed) -> {
            TripAttachmentReadEvent event = (TripAttachmentReadEvent) observed;
            if (event.outcome() == TripAttachmentReadEvent.Outcome.FOLLOWER) {
                joined.countDown();
            }
        };
        TripAttachmentResponseCacheService service = service();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TripAttachmentListResponse> first = executor.submit(() -> service.read(query,
                    (AttachmentPageData data) -> {
                        mapping.countDown();
                        await(release);
                        return response;
                    }));
            try {
                await(mapping);
                clock.set(1_950_000_000L);
                Future<TripAttachmentListResponse> second = executor.submit(() -> service.read(query, mapper));
                await(joined);
                clock.set(2_100_000_000L);
                release.countDown();
                assertThatThrownBy(() -> first.get(3, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(AttachmentReadRejectedException.class);
                assertThat(second.get(3, TimeUnit.SECONDS)).isSameAs(response);
            } finally {
                release.countDown();
            }
        }
        verify(store).publishResponse(any(), any(), anyLong(), any());
    }

    @Test
    void 잠금_대기_시간이_초과돼도_후속_요청은_마감을_유지하며_재합류한다() throws Exception {
        CountDownLatch polling = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);
        CountDownLatch rejoined = new CountDownLatch(1);
        AtomicReference<CachedAttachmentResponse> remote = new AtomicReference<>();
        when(locks.tryAcquire(any(), eq("response-lock"), anyLong())).thenAnswer(invocation -> {
            polling.countDown();
            return Optional.empty();
        });
        when(store.findResponse(key)).thenAnswer(invocation -> Optional.ofNullable(remote.get()));
        events = (Object observed) -> {
            TripAttachmentReadEvent event = (TripAttachmentReadEvent) observed;
            if (event.outcome() == TripAttachmentReadEvent.Outcome.FOLLOWER) {
                joined.countDown();
            }
            if (event.outcome() == TripAttachmentReadEvent.Outcome.REJOINED) {
                rejoined.countDown();
            }
        };
        TripAttachmentResponseCacheService service = service();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TripAttachmentListResponse> first = executor.submit(() -> service.read(query, mapper));
            await(polling);
            clock.set(1_900_000_000L);
            Future<TripAttachmentListResponse> second = executor.submit(() -> service.read(query, mapper));
            await(joined);
            clock.set(2_000_000_000L);
            assertThatThrownBy(() -> first.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(AttachmentReadRejectedException.class);
            await(rejoined);
            clock.set(2_100_000_000L);
            remote.set(cached(2_400_000_000L));
            assertThat(second.get(3, TimeUnit.SECONDS)).isSameAs(response);
        }
        verify(reader, never()).readShared(any());
        verify(locks, never()).release(any(), eq("response-lock"), any());
    }

    @Test
    void DB_실패를_재시도하지_않고_정리_실패가_원인을_가리지_않는다() {
        IllegalStateException databaseFailure = new IllegalStateException(TripAttachmentCacheErrorMessage.DATABASE_FAILED.message());
        when(reader.readShared(any())).thenThrow(databaseFailure);
        when(locks.release(any(), eq("response-lock"), any())).thenThrow(new IllegalStateException(TripAttachmentCacheErrorMessage.CLEANUP_FAILED.message()));
        assertThatThrownBy(() -> service().read(query, mapper)).isSameAs(databaseFailure);
        verify(reader).readShared(any());
        verify(store, never()).publishResponse(any(), any(), anyLong(), any());
    }

    @Test
    void 잘못된_커서라도_권한을_먼저_검증한다() {
        when(reader.normalizeKey(query)).thenThrow(new InvalidCursorException());
        doThrow(new PlaceFolderNotFoundException()).when(reader).validateTarget(2L, 3L);
        assertThatThrownBy(() -> service().read(query, mapper)).isInstanceOf(PlaceFolderNotFoundException.class);
        verifyNoInteractions(store);
    }

    @Test
    void 외부_트랜잭션이_활성화되면_캐시_조회_전에_거부한다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service().read(query, mapper)).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(store);
    }

    @Test
    void 후속_요청의_인터럽트가_선행_요청을_취소하지_않는다() throws Exception {
        CountDownLatch mapping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<AttachmentReadRejectedException.Reason> reason = new AtomicReference<>();
        events = (Object observed) -> {
            TripAttachmentReadEvent event = (TripAttachmentReadEvent) observed;
            if (event.outcome() == TripAttachmentReadEvent.Outcome.FOLLOWER) {
                joined.countDown();
            }
        };
        TripAttachmentResponseCacheService service = service();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TripAttachmentListResponse> leader = executor.submit(() -> service.read(query,
                    (AttachmentPageData data) -> {
                        mapping.countDown();
                        await(release);
                        return response;
                    }));
            try {
                await(mapping);
                Thread follower = Thread.ofVirtual().start(() -> {
                    try {
                        service.read(query, mapper);
                    } catch (AttachmentReadRejectedException failure) {
                        reason.set(failure.reason());
                        interrupted.set(Thread.currentThread().isInterrupted());
                    }
                });
                await(joined);
                follower.interrupt();
                assertThat(follower.join(java.time.Duration.ofSeconds(3))).isTrue();
                assertThat(reason.get()).isEqualTo(AttachmentReadRejectedException.Reason.INTERRUPTED);
                assertThat(interrupted).isTrue();
                release.countDown();
                assertThat(leader.get(3, TimeUnit.SECONDS)).isSameAs(response);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void 매핑_실패를_공유하고_다음_요청에서_복구한다() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);
        IllegalStateException failure = new IllegalStateException(TripAttachmentCacheErrorMessage.SIGNING_FAILED.message());
        when(reader.readShared(any())).thenAnswer(invocation -> {
            reading.countDown();
            await(release);
            return page;
        });
        events = (Object observed) -> {
            TripAttachmentReadEvent event = (TripAttachmentReadEvent) observed;
            if (event.outcome() == TripAttachmentReadEvent.Outcome.FOLLOWER) {
                joined.countDown();
            }
        };
        TripAttachmentResponseCacheService service = service();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TripAttachmentListResponse> leader = executor.submit(() -> service.read(query,
                    (AttachmentPageData data) -> {
                        throw failure;
                    }));
            try {
                await(reading);
                Future<TripAttachmentListResponse> follower = executor.submit(() -> service.read(query, mapper));
                await(joined);
                release.countDown();
                for (Future<TripAttachmentListResponse> result : List.of(leader, follower)) {
                    assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class).hasCause(failure);
                }
            } finally {
                release.countDown();
            }
        }
        verify(reader).readShared(any());
        verify(store, never()).publishResponse(any(), any(), anyLong(), any());
        assertThat(service.read(query, mapper)).isSameAs(response);
    }

    @Test
    void 마감_후의_DB_실패도_원인을_유지한다() {
        IllegalStateException failure = new IllegalStateException(TripAttachmentCacheErrorMessage.DATABASE_FAILED.message());
        when(reader.readShared(any())).thenAnswer(invocation -> {
            clock.set(3_000_000_000L);
            throw failure;
        });
        assertThatThrownBy(() -> service().read(query, mapper)).isSameAs(failure);
    }

    @Test
    void 갱신_시작시_이미_만료됐으면_DB를_조회하지_않는다() {
        doAnswer(invocation -> new AttachmentReadRefresh(
                "value", "lock", invocation.getArgument(1), "expired", clock.get(), 1000L, 999L))
                .when(store).beginResponseRefresh(any(), any());
        assertRejected(() -> service().read(query, mapper), AttachmentReadRejectedException.Reason.RETRY_EXHAUSTED);
        verify(reader, never()).readShared(any());
        verify(store, never()).publishResponse(any(), any(), anyLong(), any());
    }

    @Test
    void 갱신_시작_중_요청_마감을_넘으면_DB_작업을_시작하지_않는다() {
        when(locks.tryAcquire(any(), eq("response-lock"), anyLong())).thenAnswer(invocation -> {
            clock.set(1_900_000_000L);
            return Optional.of(ownerLock);
        });
        doAnswer(invocation -> {
            long started = clock.get();
            clock.addAndGet(150_000_000L);
            return new AttachmentReadRefresh("value", "lock", invocation.getArgument(1),
                    "delayed", started, 1000L, 1345L);
        }).when(store).beginResponseRefresh(any(), any());
        AtomicInteger mappings = new AtomicInteger();

        assertRejected(() -> service().read(query, data -> {
            mappings.incrementAndGet();
            return response;
        }), AttachmentReadRejectedException.Reason.TIMEOUT);

        verify(reader, never()).readShared(any());
        verify(store, never()).publishResponse(any(), any(), anyLong(), any());
        verify(locks).release(any(), eq("response-lock"), any());
        assertThat(mappings).hasValue(0);
    }

    @Test
    void 관측자_실패가_비즈니스_결과를_실패시키지_않는다() {
        events = (Object event) -> {
            throw new IllegalStateException(TripAttachmentCacheErrorMessage.OBSERVER_FAILED.message());
        };
        assertThat(service().read(query, mapper)).isSameAs(response);
    }

    @Test
    void 권한_없는_사용자는_이미_캐시된_응답도_조회할_수_없다() {
        when(access.requireReadable(anyLong(), anyLong(), any())).thenThrow(new PlaceFolderNotFoundException());
        assertThatThrownBy(() -> service().read(query, mapper)).isInstanceOf(PlaceFolderNotFoundException.class);
        verifyNoInteractions(store, reader);
    }

    @Test
    void 권한_확인_0밀리초와_응답_생성_400밀리초_후_600밀리초_반환시_권한을_재검증한다() {
        TripReadPermit original = new TripReadPermit(1L, 2L, "old", 495_000_000L, null);
        when(access.requireReadable(anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            invocation.<AttachmentReadContext>getArgument(2).onCacheMiss();
            return original;
        });
        when(locks.tryAcquire(any(), eq("response-lock"), anyLong())).thenAnswer(invocation -> {
            clock.set(400_000_000L);
            return Optional.of(ownerLock);
        });
        when(access.revalidate(any(), any())).thenAnswer(invocation -> {
            assertThat(clock.get()).isEqualTo(600_000_000L);
            assertThat(original.validUntilNanos()).isLessThan(clock.get());
            return new TripReadPermit(1L, 2L, "new", 1_095_000_000L, null);
        });
        assertThat(service().read(query, data -> {
            clock.set(600_000_000L);
            return response;
        })).isSameAs(response);
        verify(access).revalidate(eq(original), any());
    }

    @Test
    void 권한_재검증_중_만료된_응답은_다시_조회한다() {
        when(access.revalidate(any(), any())).thenAnswer(invocation -> {
            clock.set(900_000_000L);
            return new TripReadPermit(1L, 2L, "new", 1_395_000_000L, null);
        });
        AtomicInteger mappings = new AtomicInteger();
        assertThat(service().read(query, data -> {
            mappings.incrementAndGet();
            return response;
        })).isSameAs(response);
        assertThat(mappings).hasValue(2);
    }

    @Test
    void 권한_대기와_응답_대기는_하나의_2초_마감을_공유한다() {
        when(access.requireReadable(anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            invocation.<AttachmentReadContext>getArgument(2).onCacheMiss();
            clock.set(1_500_000_000L);
            return new TripReadPermit(1L, 2L, "access", Long.MAX_VALUE, null);
        });
        when(locks.tryAcquire(any(), eq("response-lock"), anyLong())).thenAnswer(invocation -> {
            assertThat(invocation.<Long>getArgument(2)).isLessThanOrEqualTo(500_000_000L);
            clock.set(2_100_000_000L);
            return Optional.empty();
        });
        assertRejected(() -> service().read(query, mapper), AttachmentReadRejectedException.Reason.TIMEOUT);
        verify(reader, never()).readShared(any());
    }

    @Test
    void 미완료_여행의_잘못된_커서는_충돌로_거부한다() {
        when(reader.normalizeKey(query)).thenThrow(new InvalidCursorException());
        doThrow(new TripDetailNotAvailableException())
                .when(reader).validateTarget(2L, 3L);
        assertThatThrownBy(() -> service().read(query, mapper))
                .isInstanceOf(TripDetailNotAvailableException.class);
        verifyNoInteractions(store);
    }

    @Test
    void 조회_가능_대상의_잘못된_커서는_권한_재검증_후_거부한다() {
        when(reader.normalizeKey(query)).thenThrow(new InvalidCursorException());
        assertThatThrownBy(() -> service().read(query, mapper)).isInstanceOf(InvalidCursorException.class);
        verify(access).revalidate(any(), any());
        verifyNoInteractions(store);
    }

    @Test
    void 재검증_후_만료된_권한은_반환_전에_다시_검증한다() {
        AtomicInteger revalidations = new AtomicInteger();
        when(access.revalidate(any(), any())).thenAnswer(invocation -> {
            if (revalidations.incrementAndGet() == 1) {
                clock.set(200_000_000L);
                return new TripReadPermit(1L, 2L, "expired", 100_000_000L, null);
            }
            return new TripReadPermit(1L, 2L, "fresh", 695_000_000L, null);
        });
        assertThat(service().read(query, mapper)).isSameAs(response);
        assertThat(revalidations).hasValue(2);
    }

    private TripAttachmentResponseCacheService service() {
        return new TripAttachmentResponseCacheService(reader, store, locks, access, events,
                clock::get);
    }

    private CachedAttachmentResponse cached(long deadline) {
        return new CachedAttachmentResponse(response, deadline, "remote", 4L);
    }

    private void assertRejected(Runnable action, AttachmentReadRejectedException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AttachmentReadRejectedException.class,
                (AttachmentReadRejectedException failure) -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private void delaySuccessfulReturnObservations(long delayNanos) {
        events = (Object observed) -> {
            TripAttachmentReadEvent event = (TripAttachmentReadEvent) observed;
            if (event.outcome() == TripAttachmentReadEvent.Outcome.SUCCESS
                    && event.phase() == TripAttachmentReadEvent.Phase.FRESHNESS) {
                clock.addAndGet(delayNanos);
            }
        };
    }

    private final class TestTransactions extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            transactions.incrementAndGet();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}

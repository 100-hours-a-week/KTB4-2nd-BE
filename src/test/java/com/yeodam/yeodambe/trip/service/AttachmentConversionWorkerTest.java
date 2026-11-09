package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties.Topics;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionAttemptResult;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim.Attempt;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim.Photo;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaimResult;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionStepResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.ConversionService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttachmentConversionWorkerTest {
    private static final Duration BACKOFF = Duration.ofMillis(100);
    private final Topics topics = new Topics(null, null, null, null);
    private final AttachmentConversionService db = mock(AttachmentConversionService.class);
    private final TripAttachmentDerivativeService photos = mock(TripAttachmentDerivativeService.class);
    private final Map<String, AttachmentConversionClaim> claims = new ConcurrentHashMap<>();
    private final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();
    private final List<Call> allCalls = new CopyOnWriteArrayList<>();
    private AttachmentConversionWorker worker;

    @BeforeEach
    void setUp() {
        worker = new AttachmentConversionWorker(db, photos, topics, BACKOFF);
        when(db.claim(anyString(), any(), any())).thenAnswer(call -> new AttachmentConversionClaimResult(
                AttachmentConversionClaimResult.Status.CLAIMED, claims.get(call.<String>getArgument(0)), null));
        when(db.renewLease(any())).thenReturn(true);
        when(db.releaseClaim(any())).thenReturn(true);
        when(db.beginPhoto(any(), anyLong())).thenAnswer(call -> {
            AttachmentConversionClaim claim = call.getArgument(0);
            long id = call.getArgument(1);
            return new AttachmentConversionAttemptResult(AttachmentConversionAttemptResult.Status.STARTED,
                    new Attempt(id, 1, new DerivedPhotoKeys("original/" + id, "a/" + id, "p/" + id)), null);
        });
        when(db.completePhoto(any(), anyLong(), any())).thenAnswer(call -> {
            AttachmentConversionClaim claim = call.getArgument(0);
            long id = call.getArgument(1);
            return step(id == claim.photos().getLast().itemId()
                    ? AttachmentConversionStepResult.Status.ACK_READY : AttachmentConversionStepResult.Status.CONTINUE);
        });
        when(db.finishSubBatch(any())).thenReturn(step(AttachmentConversionStepResult.Status.ACK_READY));
        when(photos.processConversionPhoto(any(), any(), any(), any())).thenAnswer(call -> {
            var entry = new Call(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), new CompletableFuture<>());
            calls.add(entry);
            allCalls.add(entry);
            return entry.result();
        });
    }

    @AfterEach
    void close() throws Exception {
        allCalls.forEach(call -> call.result().completeExceptionally(new RejectedExecutionException()));
        worker.stopAccepting();
        worker.drain().get(5, TimeUnit.SECONDS);
        worker.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void DB_확인_결과가_불확실하면_차단하고_재선점한_뒤_다음_물리_시도를_시작한다(boolean redisUnavailable) throws Exception {
        AttachmentConversionClaim first = fixture(Kind.INITIAL, Lane.LIGHT, 1);
        AttachmentConversionClaim nextOwner = new AttachmentConversionClaim(
                first.taskId(),
                "next-token",
                first.kind(),
                first.lane(),
                first.batchId(),
                first.uploadId(),
                first.executionId(),
                first.tripId(),
                first.userId(),
                first.requestId(),
                first.photos()
        );
        when(db.claim(eq(first.taskId()), any(), any())).thenReturn(
                new AttachmentConversionClaimResult(AttachmentConversionClaimResult.Status.CLAIMED, first, null),
                new AttachmentConversionClaimResult(AttachmentConversionClaimResult.Status.CLAIMED, nextOwner, null));
        AtomicBoolean uncertain = new AtomicBoolean();
        AtomicReference<RuntimeException> leaked = new AtomicReference<>();
        when(db.renewLease(any())).thenAnswer(call -> {
            if (uncertain.getAndSet(false)) {
                if (redisUnavailable) {
                    throw new TripEditLockRejectedException(
                            TripEditLockRejectedException.Reason.STORE_UNAVAILABLE);
                }
                throw new TransientDataAccessResourceException(TestErrorMessage.PUT_GUARD_DB_UNAVAILABLE.message());
            }
            return true;
        });
        when(db.finishSubBatch(first)).thenReturn(step(AttachmentConversionStepResult.Status.CONTINUE));
        when(db.beginPhoto(eq(nextOwner), anyLong())).thenReturn(new AttachmentConversionAttemptResult(
                AttachmentConversionAttemptResult.Status.STARTED, new Attempt(1L, 2, new DerivedPhotoKeys("original/1", "a/2", "p/2")), null));
        AtomicInteger starts = new AtomicInteger();
        doAnswer(call -> {
            if (starts.incrementAndGet() == 1) {
                uncertain.set(true);
                try {
                    ((BooleanSupplier) call.getArgument(3)).getAsBoolean();
                } catch (RuntimeException failure) {
                    leaked.set(failure);
                    return CompletableFuture.failedFuture(failure);
                }
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(
                        TestErrorMessage.GUARD_FENCED.message()));
            }
            Call entry = new Call(
                    call.getArgument(0),
                    call.getArgument(1),
                    call.getArgument(2),
                    call.getArgument(3),
                    new CompletableFuture<>()
            );
            calls.add(entry);
            allCalls.add(entry);
            return entry.result();
        }).when(photos).processConversionPhoto(any(), any(), any(), any());
        CompletableFuture<Void> result = process(first);
        Call resumed = next();
        try {
            assertThat(leaked.get()).isNull();
            assertThat(resumed.claim().claimToken()).isEqualTo("next-token");
            assertThat(resumed.attempt().attemptNo()).isEqualTo(2);
            assertThat(starts).hasValue(2);
        } finally {
            resumed.succeed();
        }
        result.get(5, TimeUnit.SECONDS);
        verify(db, never()).recordPhotoFailure(any(), anyLong(), anyBoolean());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void DB_결과가_막힌_동안_중단하거나_선점을_회수하면_다음_사진을_시작하지_않는다(boolean stop) throws Exception {
        var claim = fixture(Kind.INITIAL, Lane.LIGHT, 2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return step(AttachmentConversionStepResult.Status.CONTINUE);
        }).when(db).completePhoto(eq(claim), anyLong(), any());
        var result = process(claim);
        next().succeed();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> cleanup;
        if (stop) {
            worker.stopAccepting();
            cleanup = worker.drain();
        } else {
            cleanup = worker.revoke(topic(claim), Set.of(3));
        }
        try {
            release.countDown();
            cleanup.get(2, TimeUnit.SECONDS);
            assertThat(calls).isEmpty();
            assertThat(result).isNotDone();
        } finally {
            release.countDown();
            allCalls.forEach(call -> call.result().completeExceptionally(new RejectedExecutionException()));
        }
    }

    @Test
    void 잘못된_내부_라우팅은_진입_허용_검사_전에_거부한다() {
        assertThatThrownBy(() -> worker.process("id", null, Lane.LIGHT, topics.additionalLight(), 0))
                .isInstanceOf(com.yeodam.yeodambe.trip.client.InvalidAttachmentConversionMessageException.class);
        assertThatThrownBy(() -> worker.process("id", Kind.INITIAL, null, topics.initialLight(), 0))
                .isInstanceOf(com.yeodam.yeodambe.trip.client.InvalidAttachmentConversionMessageException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 사진_실패를_커밋한_뒤_ACK하거나_다음_시도를_영속화한다(boolean permanent) throws Exception {
        var claim = fixture(Kind.INITIAL, Lane.LIGHT, 1);
        var beginCount = new AtomicInteger();
        when(db.beginPhoto(eq(claim), anyLong())).thenAnswer(call -> new AttachmentConversionAttemptResult(
                AttachmentConversionAttemptResult.Status.STARTED, new Attempt(1L, beginCount.incrementAndGet(),
                new DerivedPhotoKeys("original/1", "a", "p")), null));
        when(photos.isTransientConversionFailure(any())).thenReturn(!permanent);
        var entered = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        when(db.recordPhotoFailure(eq(claim), anyLong(), eq(permanent))).thenAnswer(call -> {
            entered.countDown();
            assertThat(commit.await(5, TimeUnit.SECONDS)).isTrue();
            return step(permanent ? AttachmentConversionStepResult.Status.ACK_READY : AttachmentConversionStepResult.Status.RETRY_LATER);
        });
        var result = process(claim);
        next().result().completeExceptionally(new java.io.IOException("S3 PUT failed"));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(result).isNotDone();
        } finally {
            commit.countDown();
        }
        if (!permanent) {
            var retry = next();
            assertThat(retry.attempt().attemptNo()).isEqualTo(2);
            retry.succeed();
        }
        result.get(5, TimeUnit.SECONDS);
        verify(db).recordPhotoFailure(eq(claim), eq(1L), eq(permanent));
        assertThat(beginCount).hasValue(permanent ? 1 : 2);
    }

    @Test
    void Kafka_워커_설정은_명시적인_양수_백오프를_요구한다() {
        var runner = new ApplicationContextRunner().withUserConfiguration(AttachmentConversionWorker.class)
                .withBean(AttachmentConversionService.class, () -> db)
                .withBean(TripAttachmentDerivativeService.class, () -> photos)
                .withBean(AttachmentConversionProperties.class, () -> new AttachmentConversionProperties(
                        null,
                        null,
                        null,
                        topics,
                        null,
                        true,
                        true,
                        true
                ))
                .withBean("conversionService", ConversionService.class, ApplicationConversionService::getSharedInstance)
                .withPropertyValues("app.attachment-conversion.mode=KAFKA");
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.attachment-conversion.retry.technical-backoff=0ms")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.attachment-conversion.retry.technical-backoff=100ms")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(AttachmentConversionWorker.class));
    }

    @Test
    void DB와_HOLD와_운영_시작은_워커_수치_설정을_요구하지_않는다() {
        for (String[] config : List.of(new String[]{"app.attachment-conversion.mode=DB"},
                new String[]{"app.attachment-conversion.mode=HOLD"},
                new String[]{"app.attachment-conversion.mode=KAFKA", "app.attachment-conversion.operations.enabled=true"})) {
            new ApplicationContextRunner().withUserConfiguration(AttachmentConversionWorker.class)
                    .withPropertyValues(config).run(context -> assertThat(context).hasNotFailed()
                            .doesNotHaveBean(AttachmentConversionWorker.class));
        }
    }

    @Test
    void 소유권이_차단되어도_미완료_작업을_ACK하지_않는다() throws Exception {
        var claim = fixture(Kind.INITIAL, Lane.LIGHT, 1);
        var checked = new CountDownLatch(1);
        var polls = new AtomicInteger();
        when(db.renewLease(claim)).thenReturn(false);
        when(db.finishSubBatch(claim)).thenReturn(step(AttachmentConversionStepResult.Status.RETRY_LATER));
        when(db.claim(eq(claim.taskId()), any(), any())).thenAnswer(call -> {
            if (polls.incrementAndGet() == 1) {
                return new AttachmentConversionClaimResult(AttachmentConversionClaimResult.Status.CLAIMED, claim, null);
            }
            checked.countDown();
            return new AttachmentConversionClaimResult(AttachmentConversionClaimResult.Status.RETRY_LATER, null,
                    LocalDateTime.now().plusMinutes(10));
        });
        var result = process(claim);
        assertThat(checked.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result).isNotDone();
        verifyNoInteractions(photos);
        verify(db, never()).recordPhotoFailure(any(), anyLong(), anyBoolean());
    }

    @Test
    void 저장된_스냅샷은_새_시도_없이_부모를_완료한다() throws Exception {
        var base = fixture(Kind.INITIAL, Lane.LIGHT, 1);
        var saved = new Photo(1L, "image/jpeg", "original/1", 100, 1, null, new DerivedPhotoKeys("original/1", "a", "p"));
        var claim = new AttachmentConversionClaim(base.taskId(), base.claimToken(), base.kind(), base.lane(), base.batchId(),
                base.uploadId(), base.executionId(), base.tripId(), base.userId(), base.requestId(), List.of(saved));
        claims.put(claim.taskId(), claim);
        process(claim).get(5, TimeUnit.SECONDS);
        verify(db).finishSubBatch(claim);
        verify(db, never()).beginPhoto(any(), anyLong());
        verifyNoInteractions(photos);
    }

    @Test
    void 네_작업을_받아도_동시에_두_사진을_초과해_처리하지_않는다() throws Exception {
        var futures = new ArrayList<CompletableFuture<Void>>();
        for (Lane lane : Lane.values()) {
            for (Kind kind : Kind.values()) {
                futures.add(process(fixture(kind, lane, 1)));
            }
        }
        var one = next();
        var two = next();
        assertThat(one.claim().lane()).isNotEqualTo(two.claim().lane());
        assertThat(calls.poll(50, TimeUnit.MILLISECONDS)).isNull();
        var extra = fixture(Kind.INITIAL, Lane.LIGHT, 1);
        assertThatThrownBy(() -> process(extra)).isInstanceOf(RejectedExecutionException.class);
        one.succeed();
        two.succeed();
        next().succeed();
        next().succeed();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
    }

    @Test
    void 같은_레인은_사진마다_작업_종류를_번갈아_처리한다() throws Exception {
        var first = fixture(Kind.INITIAL, Lane.LIGHT, 2);
        var second = fixture(Kind.ADDITIONAL, Lane.LIGHT, 1);
        var a = process(first);
        var initial = next();
        var b = process(second);
        initial.succeed();
        var additional = next();
        assertThat(additional.claim().kind()).isEqualTo(Kind.ADDITIONAL);
        additional.succeed();
        var following = next();
        assertThat(following.claim().kind()).isEqualTo(Kind.INITIAL);
        following.succeed();
        CompletableFuture.allOf(a, b).get(5, TimeUnit.SECONDS);
    }

    @Test
    void 대기하거나_재시도하는_종류는_다른_종류를_막지_않는다() throws Exception {
        var delayed = fixture(Kind.INITIAL, Lane.LIGHT, 1);
        when(db.beginPhoto(eq(delayed), anyLong())).thenReturn(new AttachmentConversionAttemptResult(
                AttachmentConversionAttemptResult.Status.RETRY_LATER, null, LocalDateTime.now().plusHours(1)));
        var waiting = process(delayed);
        var runnable = process(fixture(Kind.ADDITIONAL, Lane.LIGHT, 1));
        var actual = next();
        assertThat(actual.claim().kind()).isEqualTo(Kind.ADDITIONAL);
        actual.succeed();
        runnable.get(5, TimeUnit.SECONDS);
        assertThat(waiting).isNotDone();
        verify(photos, never()).processConversionPhoto(eq(delayed), any(), any(), any());
    }

    @Test
    void DB_결과_재시도는_사진을_재변환하지_않는다() throws Exception {
        var first = fixture(Kind.INITIAL, Lane.LIGHT, 1);
        var saves = new AtomicInteger();
        var failure = new CountDownLatch(1);
        doAnswer(call -> {
            if (saves.incrementAndGet() == 1) {
                failure.countDown();
                throw new TransientDataAccessResourceException("commit response lost");
            }
            return step(AttachmentConversionStepResult.Status.ACK_READY);
        }).when(db).completePhoto(eq(first), anyLong(), any());
        var result = process(first);
        var photo = next();
        var other = process(fixture(Kind.ADDITIONAL, Lane.LIGHT, 1));
        photo.succeed();
        assertThat(failure.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result).isNotDone();
        assertThat(calls.poll(50, TimeUnit.MILLISECONDS)).isNull();
        result.get(5, TimeUnit.SECONDS);
        var additional = next();
        additional.succeed();
        other.get(5, TimeUnit.SECONDS);
        verify(photos, times(1)).processConversionPhoto(eq(first), any(), any(), any());
        verify(db, times(2)).completePhoto(eq(first), anyLong(), eq(photo.keys()));
    }

    @Test
    void 기술적_실패는_시도_횟수를_소모하지_않고_대기_후_재시도한다() throws Exception {
        var claim = fixture(Kind.INITIAL, Lane.LIGHT, 1);
        var attempts = new AtomicInteger();
        var firstAt = new AtomicLong();
        var secondAt = new AtomicLong();
        when(db.beginPhoto(eq(claim), anyLong())).thenAnswer(call -> {
            if (attempts.incrementAndGet() == 1) {
                firstAt.set(System.nanoTime());
                throw new TransientDataAccessResourceException("begin response lost");
            }
            secondAt.set(System.nanoTime());
            return new AttachmentConversionAttemptResult(AttachmentConversionAttemptResult.Status.STARTED,
                    new Attempt(claim.photos().getFirst().itemId(), 1, new DerivedPhotoKeys("original", "a", "p")), null);
        });
        var result = process(claim);
        var photo = next();
        assertThat(Duration.ofNanos(secondAt.get() - firstAt.get())).isGreaterThanOrEqualTo(BACKOFF);
        photo.succeed();
        result.get(5, TimeUnit.SECONDS);
        verify(db, never()).recordPhotoFailure(any(), anyLong(), anyBoolean());
    }

    @Test
    void 종료는_네이티브_처리와_PUT_정리를_기다린다() throws Exception {
        var claim = fixture(Kind.INITIAL, Lane.LIGHT, 2);
        var result = process(claim);
        var photo = next();
        worker.stopAccepting();
        var drained = worker.drain();
        assertThat(drained).isNotDone();
        assertThat(photo.active().getAsBoolean()).isTrue();
        assertThat(photo.result()).isNotCancelled();
        photo.succeed();
        drained.get(5, TimeUnit.SECONDS);
        verify(db).completePhoto(eq(claim), anyLong(), any());
        verify(db).releaseClaim(claim);
        assertThat(result).isNotDone();
        assertThat(calls).isEmpty();
    }

    @Test
    void 긴_큐_대기는_시도_횟수를_소모하지_않는다() throws Exception {
        var claim = fixture(Kind.INITIAL, Lane.LIGHT, 1);
        when(db.claim(eq(claim.taskId()), any(), any())).thenReturn(new AttachmentConversionClaimResult(
                AttachmentConversionClaimResult.Status.RETRY_LATER, null, LocalDateTime.now().plusYears(1)));
        var result = process(claim);
        var observed = new CountDownLatch(2);
        doAnswer(call -> {
            observed.countDown();
            return new AttachmentConversionClaimResult(AttachmentConversionClaimResult.Status.RETRY_LATER, null,
                    LocalDateTime.now().plusYears(1));
        }).when(db).claim(eq(claim.taskId()), any(), any());
        assertThat(observed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result).isNotDone();
        verify(db, never()).beginPhoto(any(), anyLong());
        verifyNoInteractions(photos);
    }

    @Test
    void 해제된_선점은_기존_임대_만료_전에_확인한다() throws Exception {
        var claim = fixture(Kind.INITIAL, Lane.LIGHT, 1);
        when(db.claim(eq(claim.taskId()), any(), any())).thenReturn(
                new AttachmentConversionClaimResult(AttachmentConversionClaimResult.Status.RETRY_LATER, null, LocalDateTime.now().plusMinutes(10)),
                new AttachmentConversionClaimResult(AttachmentConversionClaimResult.Status.CLAIMED, claim, null));
        var result = process(claim);
        next().succeed();
        result.get(5, TimeUnit.SECONDS);
        verify(db, times(2)).claim(eq(claim.taskId()), eq(claim.kind()), eq(claim.lane()));
    }

    @Test
    void 선점_회수는_미완료_작업을_ACK하지_않고_DB_확정과_선점_해제를_기다린다() throws Exception {
        var claim = fixture(Kind.INITIAL, Lane.LIGHT, 2);
        var result = process(claim);
        var photo = next();
        var released = new AtomicInteger();
        when(db.releaseClaim(claim)).thenAnswer(call -> {
            if (released.incrementAndGet() == 1) {
                throw new TransientDataAccessResourceException("release commit uncertain");
            }
            return true;
        });
        var cleanup = worker.revoke(topic(claim), Set.of(3));
        assertThat(cleanup).isNotDone();
        assertThat(photo.active().getAsBoolean()).isTrue();
        assertThatThrownBy(() -> process(fixture(Kind.INITIAL, Lane.LIGHT, 1))).isInstanceOf(RejectedExecutionException.class);
        photo.succeed();
        cleanup.get(5, TimeUnit.SECONDS);
        assertThat(released).hasValue(2);
        assertThat(result).isNotDone();
        var successor = process(fixture(Kind.INITIAL, Lane.LIGHT, 1));
        next().succeed();
        successor.get(5, TimeUnit.SECONDS);
    }

    @Test
    void 스케줄러_거부는_비즈니스_실패_없이_같은_시도를_재시도한다() throws Exception {
        var claim = fixture(Kind.INITIAL, Lane.LIGHT, 1);
        var result = process(claim);
        next().result().completeExceptionally(new RejectedExecutionException("scheduler full"));
        var retry = next();
        retry.succeed();
        result.get(5, TimeUnit.SECONDS);
        verify(db, never()).recordPhotoFailure(any(), anyLong(), anyBoolean());
    }

    @Test
    void 시작_결과가_ALREADY_SAVED나_ACK_READY이면_사진을_처리하지_않는다() throws Exception {
        var claim = fixture(Kind.INITIAL, Lane.LIGHT, 2);
        when(db.beginPhoto(eq(claim), anyLong())).thenReturn(
                new AttachmentConversionAttemptResult(AttachmentConversionAttemptResult.Status.ALREADY_SAVED, null, null),
                new AttachmentConversionAttemptResult(AttachmentConversionAttemptResult.Status.ACK_READY, null, null));
        process(claim).get(5, TimeUnit.SECONDS);
        verifyNoInteractions(photos);
    }

    @Test
    void 소유권이_차단된_사진은_DB_결과가_확정될_때까지_레인을_유지한다() throws Exception {
        var claim = fixture(Kind.INITIAL, Lane.LIGHT, 1);
        var first = process(claim);
        var photo = next();
        var failed = new CountDownLatch(1);
        var confirmed = new AtomicInteger();
        when(db.finishSubBatch(claim)).thenAnswer(call -> {
            if (confirmed.incrementAndGet() == 1) {
                failed.countDown();
                throw new TransientDataAccessResourceException("terminal confirmation uncertain");
            }
            return step(AttachmentConversionStepResult.Status.ACK_READY);
        });
        var other = process(fixture(Kind.ADDITIONAL, Lane.LIGHT, 1));
        photo.result().completeExceptionally(new java.util.concurrent.CancellationException("lease lost"));
        assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(first).isNotDone();
        assertThat(calls.poll(50, TimeUnit.MILLISECONDS)).isNull();
        first.get(5, TimeUnit.SECONDS);
        next().succeed();
        other.get(5, TimeUnit.SECONDS);
        verify(db, never()).recordPhotoFailure(any(), anyLong(), anyBoolean());
    }

    @Test
    void 중단은_긴_백오프를_기다리지_않고_유휴_선점을_해제한다() throws Exception {
        worker.close();
        worker = new AttachmentConversionWorker(db, photos, topics, Duration.ofMinutes(2));
        var claim = fixture(Kind.INITIAL, Lane.LIGHT, 1);
        var failed = new CountDownLatch(1);
        when(db.beginPhoto(eq(claim), anyLong())).thenAnswer(call -> {
            failed.countDown();
            throw new TransientDataAccessResourceException("begin uncertain");
        });
        var process = process(claim);
        assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
        worker.drain().get(2, TimeUnit.SECONDS);
        assertThat(process).isNotDone();
        verify(db).releaseClaim(claim);
        verifyNoInteractions(photos);
    }

    @Test
    void 기술적_백오프가_갱신_주기보다_길어도_임대를_갱신한다() throws Exception {
        worker.close();
        worker = new AttachmentConversionWorker(db, photos, topics, Duration.ofMinutes(2));
        var claim = fixture(Kind.INITIAL, Lane.LIGHT, 1);
        var renewal = new CountDownLatch(1);
        var renewals = new AtomicInteger();
        when(db.renewLease(claim)).thenAnswer(call -> {
            if (renewals.incrementAndGet() >= 2) {
                renewal.countDown();
            }
            return true;
        });
        when(db.beginPhoto(eq(claim), anyLong())).thenThrow(new TransientDataAccessResourceException("DB temporary"));
        var result = process(claim);
        assertThat(renewal.await(35, TimeUnit.SECONDS)).isTrue();
        assertThat(result).isNotDone();
        verify(db, times(1)).beginPhoto(eq(claim), anyLong());
        verify(db, never()).recordPhotoFailure(any(), anyLong(), anyBoolean());
    }

    private AttachmentConversionClaim fixture(Kind kind, Lane lane, int count) {
        var input = new ArrayList<Photo>();
        for (long id = 1; id <= count; id++) {
            input.add(new Photo(id, lane == Lane.HEIC ? "image/heic" : "image/jpeg", "original/" + id, 100, 0, null, null));
        }
        var claim = new AttachmentConversionClaim(UUID.randomUUID().toString(), "token", kind, lane, 1L,
                "upload", UUID.randomUUID().toString(), 1L, 1L, "request", input);
        claims.put(claim.taskId(), claim);
        return claim;
    }

    private CompletableFuture<Void> process(AttachmentConversionClaim claim) {
        return worker.process(claim.taskId(), claim.kind(), claim.lane(), topic(claim), 3);
    }

    private String topic(AttachmentConversionClaim claim) {
        return claim.kind() == Kind.INITIAL
                ? claim.lane() == Lane.HEIC ? topics.initialHeic() : topics.initialLight()
                : claim.lane() == Lane.HEIC ? topics.additionalHeic() : topics.additionalLight();
    }

    private Call next() throws Exception {
        var call = calls.poll(5, TimeUnit.SECONDS);
        assertThat(call).isNotNull();
        return call;
    }

    private AttachmentConversionStepResult step(AttachmentConversionStepResult.Status status) {
        return new AttachmentConversionStepResult(status, null);
    }

    private record Call(
            AttachmentConversionClaim claim,
            Photo photo,
            Attempt attempt,
            BooleanSupplier active,
            CompletableFuture<DerivedPhotoKeys> result
    ) {
        DerivedPhotoKeys keys() {
            return new DerivedPhotoKeys(photo.objectKey(), "analyze/" + photo.itemId(), "preview/" + photo.itemId());
        }

        void succeed() {
            result.complete(keys());
        }
    }

    private enum TestErrorMessage {
        PUT_GUARD_DB_UNAVAILABLE("PUT guard DB unavailable"),
        GUARD_FENCED("guard fenced");

        private final String message;

        TestErrorMessage(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }
}

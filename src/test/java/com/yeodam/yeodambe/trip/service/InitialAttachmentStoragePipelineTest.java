package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.trip.service.response.PreparedPhotoDerivative;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.slf4j.MDC;
import org.springframework.dao.TransientDataAccessResourceException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InitialAttachmentStoragePipelineTest {
    @TempDir
    Path directory;
    private TripAttachmentStorageClient storage;
    private InitialAttachmentUploadTransactionService transactions;
    private TripDerivativeScheduler scheduler;
    private InitialAttachmentStoragePipeline pipeline;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        storage = mock(TripAttachmentStorageClient.class);
        transactions = mock(InitialAttachmentUploadTransactionService.class);
        meters = new SimpleMeterRegistry();
        scheduler = new TripDerivativeScheduler(1, 1, 10, 20, meters);
        pipeline = new InitialAttachmentStoragePipeline(storage, transactions, scheduler, meters, 128 * 1024 * 1024L);
        when(transactions.renewLease(any())).thenReturn(true);
        when(transactions.startPhotoAttempt(any(), any())).thenAnswer(call -> {
            Long id = call.getArgument(1);
            return List.of(id + "/analyze", id + "/preview");
        });
        when(storage.storeDerivedToAsync(anyString(), any(), anyString()))
                .thenAnswer(call -> CompletableFuture.completedFuture(call.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        pipeline.close();
        scheduler.stop();
        meters.close();
    }

    @Test
    void 변환과_비동기_저장에_같은_작업문맥을_전달한다() throws Exception {
        var claim = claim(1L);
        AtomicReference<Map<String, String>> convertedContext = new AtomicReference<>();
        AtomicReference<Map<String, String>> uploadedContext = new AtomicReference<>();
        when(storage.storeDerivedToAsync(anyString(), any(), anyString())).thenAnswer(call -> {
            uploadedContext.set(MDC.getCopyOfContextMap());
            return CompletableFuture.completedFuture(call.getArgument(0));
        });
        MDC.put("request_id", "upload-request");
        try {
            pipeline.start(claim, (photo, keys) -> {
                convertedContext.set(MDC.getCopyOfContextMap());
                return prepare(photo.itemId(), keys);
            }, error -> true);
            verify(transactions, timeout(5000)).completeBatch(claim);
            Map<String, String> expected = Map.of(
                    "request_id", "upload-request",
                    "workflow", "trip_creation",
                    "trip_id", claim.tripId().toString(),
                    "job_id", claim.executionId(),
                    "batch_no", Integer.toString(claim.batchNo())
            );
            assertThat(convertedContext.get()).containsAllEntriesOf(expected)
                    .containsEntry("item_id", "1")
                    .containsEntry("attempts", "1");
            assertThat(uploadedContext.get()).containsAllEntriesOf(expected);
            assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("request_id", "upload-request"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void A_업로드_중_B를_변환하고_C는_자리가_날_때까지_시작하지_않는다() throws Exception {
        InitialAttachmentUploadBatchSnapshot claim = claim(1L, 2L, 3L);
        CompletableFuture<String> upload = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        AtomicBoolean third = new AtomicBoolean();
        AtomicReference<Path> firstFile = new AtomicReference<>();
        when(storage.storeDerivedToAsync(eq("1/analyze"), any(), eq("image/jpeg")))
                .thenAnswer(call -> {
                    firstFile.set(call.getArgument(1));
                    started.countDown();
                    return upload;
                });
        pipeline.start(claim, (photo, keys) -> {
            if (photo.itemId() == 2L) {
                second.countDown();
            }
            if (photo.itemId() == 3L) {
                third.set(true);
            }
            return prepare(photo.itemId(), keys);
        }, error -> true);

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(firstFile.get()).exists();
        assertThat(third).isFalse();
        verify(transactions, never()).savePhotoSuccess(any(), any(), any());
        verify(transactions, never()).completeBatch(any());
        upload.complete("1/analyze");
        verify(transactions, timeout(5000)).completeBatch(claim);
        assertThat(firstFile.get()).doesNotExist();
        assertThat(third).isTrue();
        InOrder order = inOrder(transactions);
        order.verify(transactions).savePhotoSuccess(eq(claim), eq(1L), any());
        order.verify(transactions).savePhotoSuccess(eq(claim), eq(2L), any());
        order.verify(transactions).savePhotoSuccess(eq(claim), eq(3L), any());
    }

    @Test
    void A_저장_실패시_B_변환_종료를_기다린_뒤_한번만_재시도한다() throws Exception {
        InitialAttachmentUploadBatchSnapshot claim = claim(1L, 2L);
        CompletableFuture<String> upload = new CompletableFuture<>();
        CountDownLatch second = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(storage.storeDerivedToAsync(eq("1/analyze"), any(), eq("image/jpeg"))).thenReturn(upload);
        pipeline.start(claim, (photo, keys) -> {
            if (photo.itemId() == 2L) {
                second.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test timeout");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }
            return prepare(photo.itemId(), keys);
        }, error -> true);
        assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
        upload.completeExceptionally(new IOException("network"));
        verify(transactions, after(100).never()).retryBatch(any(), any(), anyBoolean());
        release.countDown();
        verify(transactions, timeout(5000).times(1)).retryBatch(claim, 1L, false);
        verify(transactions, never()).savePhotoSuccess(any(), any(), any());
        verify(transactions, never()).completeBatch(any());
        verify(storage, never()).storeDerivedToAsync(eq("2/analyze"), any(), any());
    }

    @Test
    void 취소되어도_진행중_PUT이_끝나기_전에는_파일을_삭제하지_않는다() throws Exception {
        InitialAttachmentUploadBatchSnapshot claim = claim(1L);
        CompletableFuture<String> upload = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Path> file = new AtomicReference<>();
        when(storage.storeDerivedToAsync(eq("1/analyze"), any(), eq("image/jpeg")))
                .thenAnswer(call -> {
                    file.set(call.getArgument(1));
                    started.countDown();
                    return upload;
                });
        pipeline.start(claim, (photo, keys) -> prepare(photo.itemId(), keys), error -> true);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        pipeline.cancelExecution(claim.executionId());
        assertThat(file.get()).exists();
        upload.complete("1/analyze");
        verify(transactions, timeout(5000)).retryBatch(eq(claim), eq(1L), eq(false));
        assertThat(file.get()).doesNotExist();
        verify(transactions, never()).savePhotoSuccess(any(), any(), any());
    }

    @Test
    void DB_성공_기록_실패는_배치를_완료하지_않고_파일을_정리한다() throws Exception {
        InitialAttachmentUploadBatchSnapshot claim = claim(1L);
        AtomicReference<Path> file = new AtomicReference<>();
        doThrow(new TransientDataAccessResourceException("DB"))
                .when(transactions).savePhotoSuccess(any(), any(), any());
        pipeline.start(claim, (photo, keys) -> {
            PreparedPhotoDerivative prepared = prepare(photo.itemId(), keys);
            file.set(prepared.outputs().getFirst().file());
            return prepared;
        }, error -> true);

        verify(transactions, timeout(5000)).retryBatch(claim, 1L, false);
        assertThat(file.get()).doesNotExist();
        verify(transactions, never()).completeBatch(any());
    }

    @Test
    void 업로드_중_선점을_잃으면_늦은_결과를_DB에_반영하지_않는다() throws Exception {
        InitialAttachmentUploadBatchSnapshot claim = claim(1L);
        CompletableFuture<String> upload = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean current = new AtomicBoolean(true);
        when(transactions.renewLease(any())).thenAnswer(call -> current.get());
        when(storage.storeDerivedToAsync(eq("1/analyze"), any(), eq("image/jpeg")))
                .thenAnswer(call -> {
                    started.countDown();
                    return upload;
                });
        pipeline.start(claim, (photo, keys) -> prepare(photo.itemId(), keys), error -> true);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        current.set(false);
        upload.complete("1/analyze");

        verify(transactions, timeout(5000)).retryBatch(claim, 1L, false);
        verify(transactions, never()).savePhotoSuccess(any(), any(), any());
        verify(storage, never()).storeDerivedToAsync(eq("1/preview"), any(), any());
    }

    @Test
    void 서로_다른_배치도_전역_두_자리만_사용한다() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InitialAttachmentUploadBatchSnapshot first = claim(1L);
        InitialAttachmentUploadBatchSnapshot second = new InitialAttachmentUploadBatchSnapshot(
                2L, "upload-2", "execution-2", 8L, 1L, 1, 1, true,
                InitialAttachmentUploadStatus.CONVERTING, "token-2", first.photos());
        InitialAttachmentUploadBatchSnapshot third = new InitialAttachmentUploadBatchSnapshot(
                3L, "upload-3", "execution-3", 9L, 1L, 1, 1, true,
                InitialAttachmentUploadStatus.CONVERTING, "token-3", first.photos());
        BiFunction<InitialAttachmentUploadBatchSnapshot.Photo, List<String>, PreparedPhotoDerivative> prepare =
                (photo, keys) -> {
                    firstStarted.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test timeout");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                    return prepare(photo.itemId(), keys);
                };
        try {
            pipeline.start(first, prepare, error -> true);
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            pipeline.start(second, prepare, error -> true);
            pipeline.start(third, prepare, error -> true);
            verify(transactions).releaseSaturatedBatch(third);
            verify(transactions, never()).startPhotoAttempt(eq(third), any());
        } finally {
            release.countDown();
        }
        verify(transactions, timeout(5000)).completeBatch(first);
        verify(transactions, timeout(5000)).completeBatch(second);
    }

    @Test
    void 다음_변환_제출이_포화돼도_이미_진행한_사진의_저장은_완료한다() throws Exception {
        TripDerivativeScheduler rejecting = mock(TripDerivativeScheduler.class);
        AtomicInteger submissions = new AtomicInteger();
        CountDownLatch rejected = new CountDownLatch(1);
        when(rejecting.submitPrepared(anyString(), anyString(), any(), any())).thenAnswer(call -> {
            if (submissions.incrementAndGet() == 2) {
                rejected.countDown();
                throw new RejectedExecutionException("full");
            }
            return scheduler.submitPrepared(call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3));
        });
        InitialAttachmentStoragePipeline limited = new InitialAttachmentStoragePipeline(
                storage, transactions, rejecting, meters, 134217728L);
        CompletableFuture<String> upload = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        InitialAttachmentUploadBatchSnapshot claim = claim(1L, 2L);
        when(storage.storeDerivedToAsync(eq("1/analyze"), any(), eq("image/jpeg")))
                .thenAnswer(call -> {
                    started.countDown();
                    return upload;
                });
        try {
            limited.start(claim, (photo, keys) -> prepare(photo.itemId(), keys), error -> true);
            assertThat(rejected.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            upload.complete("1/analyze");
            verify(transactions, timeout(5000)).savePhotoSuccess(eq(claim), eq(1L), any());
            verify(transactions, timeout(5000)).releaseSaturatedBatch(claim);
            verify(transactions, never()).retryBatch(any(), any(), anyBoolean());
            verify(transactions, never()).startPhotoAttempt(claim, 2L);
        } finally {
            upload.complete("1/analyze");
            limited.close();
        }
    }

    @Test
    void 저장_bytes가_한도에_도달하면_새_배치를_선점_반환한다() throws Exception {
        InitialAttachmentStoragePipeline limited = new InitialAttachmentStoragePipeline(
                storage, transactions, scheduler, meters, 1);
        CompletableFuture<String> upload = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        when(storage.storeDerivedToAsync(eq("1/analyze"), any(), eq("image/jpeg")))
                .thenAnswer(call -> {
                    started.countDown();
                    return upload;
                });
        InitialAttachmentUploadBatchSnapshot claim = claim(1L);
        InitialAttachmentUploadBatchSnapshot other = new InitialAttachmentUploadBatchSnapshot(
                2L, "other", "other", 8L, 1L, 1, 1, true,
                InitialAttachmentUploadStatus.CONVERTING, "token", claim.photos());
        try {
            limited.start(claim, (photo, keys) -> prepare(photo.itemId(), keys), error -> true);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            limited.start(other, (photo, keys) -> prepare(photo.itemId(), keys), error -> true);
            verify(transactions).releaseSaturatedBatch(other);
            verify(transactions, never()).startPhotoAttempt(eq(other), any());
        } finally {
            upload.complete("1/analyze");
            limited.close();
        }
    }

    @Test
    void 마지막_사진이_기존_성공_체크포인트이면_재변환하지_않고_배치를_완료한다() {
        InitialAttachmentUploadBatchSnapshot original = claim(1L, 2L);
        InitialAttachmentUploadBatchSnapshot.Photo saved = original.photos().get(1);
        InitialAttachmentUploadBatchSnapshot claim = new InitialAttachmentUploadBatchSnapshot(
                1L, "upload", "execution", 7L, 1L, 1, 2, true,
                InitialAttachmentUploadStatus.CONVERTING, "token", List.of(original.photos().getFirst(),
                new InitialAttachmentUploadBatchSnapshot.Photo(saved.itemId(), saved.fileName(), saved.contentType(),
                        saved.sizeBytes(), saved.objectKey(), 1, null, new DerivedPhotoKeys("original/2", "a", "p"))));
        pipeline.start(claim, (photo, keys) -> prepare(photo.itemId(), keys), error -> true);
        verify(transactions, timeout(5000)).completeBatch(claim);
        verify(transactions, never()).startPhotoAttempt(claim, 2L);
    }

    @Test
    void 종료는_진행중_PUT의_종료까지_파일을_유지하고_늦은_성공을_차단한다() throws Exception {
        InitialAttachmentUploadBatchSnapshot claim = claim(1L);
        CompletableFuture<String> upload = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Path> file = new AtomicReference<>();
        when(storage.storeDerivedToAsync(eq("1/analyze"), any(), eq("image/jpeg")))
                .thenAnswer(call -> {
                    file.set(call.getArgument(1));
                    started.countDown();
                    return upload;
                });
        pipeline.start(claim, (photo, keys) -> prepare(photo.itemId(), keys), error -> true);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread closing = new Thread(pipeline::close);
        try {
            closing.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (closing.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(closing.getState()).isEqualTo(Thread.State.TIMED_WAITING);
            assertThat(file.get()).exists();
            upload.complete("1/analyze");
            closing.join(5000);
            assertThat(closing.isAlive()).isFalse();
            assertThat(file.get()).doesNotExist();
            verify(transactions).retryBatch(claim, 1L, false);
            verify(transactions, never()).savePhotoSuccess(any(), any(), any());
            verify(storage, never()).storeDerivedToAsync(eq("1/preview"), any(), any());
            pipeline.start(claim, (photo, keys) -> prepare(photo.itemId(), keys), error -> true);
            verify(transactions).releaseSaturatedBatch(claim);
        } finally {
            upload.complete("1/analyze");
            closing.join(5000);
        }
    }

    @Test
    void 포화_반환_트랜잭션은_파이프라인_모니터를_보유하지_않는다() {
        pipeline.close();
        InitialAttachmentUploadBatchSnapshot claim = claim(1L);
        doAnswer(call -> {
            assertThat(Thread.holdsLock(pipeline)).isFalse();
            return null;
        }).when(transactions).releaseSaturatedBatch(claim);

        pipeline.start(claim, (photo, keys) -> prepare(photo.itemId(), keys), error -> true);

        verify(transactions).releaseSaturatedBatch(claim);
    }

    @Test
    void 포화_반환_DB가_지연돼도_취소_호출은_잠금에서_대기하지_않는다() throws Exception {
        pipeline.close();
        InitialAttachmentUploadBatchSnapshot claim = claim(1L);
        CountDownLatch returning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CompletableFuture<Void> canceled = new CompletableFuture<>();
        doAnswer(call -> {
            returning.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timeout");
            }
            return null;
        }).when(transactions).releaseSaturatedBatch(claim);
        Thread submitting = new Thread(() -> {
            try {
                pipeline.start(claim, (photo, keys) -> prepare(photo.itemId(), keys), error -> true);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        Thread canceling = new Thread(() -> {
            pipeline.cancelExecution(claim.executionId());
            canceled.complete(null);
        });
        try {
            submitting.start();
            assertThat(returning.await(5, TimeUnit.SECONDS)).isTrue();
            canceling.start();
            canceled.get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            submitting.join(5000);
            canceling.join(5000);
        }
        assertThat(submitting.isAlive()).isFalse();
        assertThat(canceling.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
    }

    private InitialAttachmentUploadBatchSnapshot claim(Long... ids) {
        List<InitialAttachmentUploadBatchSnapshot.Photo> photos = Arrays.stream(ids)
                .map(id -> new InitialAttachmentUploadBatchSnapshot.Photo(id, id + ".jpg", "image/jpeg",
                        1, "original/" + id, 0, null, null)).toList();
        return new InitialAttachmentUploadBatchSnapshot(1L, "upload", "execution", 7L, 1L, 1,
                ids.length, true, InitialAttachmentUploadStatus.CONVERTING, "token", photos);
    }

    private PreparedPhotoDerivative prepare(Long id, List<String> keys) {
        try {
            Path dir = Files.createTempDirectory(directory, "photo-" + id);
            Files.write(dir.resolve("original"), new byte[]{1});
            Path analyze = Files.write(dir.resolve("analyze.jpg"), new byte[]{1});
            Path preview = Files.write(dir.resolve("preview.webp"), new byte[]{1});
            DerivedPhotoKeys photo = new DerivedPhotoKeys("original/" + id, keys.get(0), keys.get(1),
                    null, null, null, null, null, 1L, 1L, 1L, null);
            return new PreparedPhotoDerivative(dir, photo, List.of(
                    new PreparedPhotoDerivative.Output(analyze, keys.get(0), "image/jpeg"),
                    new PreparedPhotoDerivative.Output(preview, keys.get(1), "image/webp")));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}

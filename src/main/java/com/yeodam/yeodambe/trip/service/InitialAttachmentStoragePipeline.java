package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot.Photo;
import com.yeodam.yeodambe.trip.service.response.PreparedPhotoDerivative;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Predicate;

@Slf4j
@Component
public class InitialAttachmentStoragePipeline {
    private final TripAttachmentStorageClient storage;
    private final InitialAttachmentUploadTransactionService transactions;
    private final TripDerivativeScheduler scheduler;
    private final long maxBytes;
    private final MeterRegistry meters;
    private final Semaphore slots = new Semaphore(2);
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor control = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().name("initial-photo-storage").daemon(true).factory());
    private final Set<Batch> batches = new LinkedHashSet<>();
    private final Deque<Pending> waiting = new ArrayDeque<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private volatile long stagedBytes;
    private boolean storing;

    public InitialAttachmentStoragePipeline(
            TripAttachmentStorageClient storage,
            InitialAttachmentUploadTransactionService transactions,
            TripDerivativeScheduler scheduler,
            MeterRegistry meters,
            @Value("${attachment.derivative.storage-max-staged-bytes:134217728}") long maxBytes
    ) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("저장 대기 파일 크기 한도는 양수여야 합니다.");
        }
        this.storage = storage;
        this.transactions = transactions;
        this.scheduler = scheduler;
        this.maxBytes = maxBytes;
        this.meters = meters;
        control.setRemoveOnCancelPolicy(true);
        Gauge.builder("yeodam.trip.derivative.storage.inflight", slots, value -> 2 - value.availablePermits())
                .register(meters);
        Gauge.builder("yeodam.trip.derivative.storage.bytes", this, value -> value.stagedBytes)
                .register(meters);
    }

    public void start(
            InitialAttachmentUploadBatchSnapshot claim,
            BiFunction<Photo, List<String>, PreparedPhotoDerivative> prepare,
            Predicate<Throwable> transientFailure
    ) {
        boolean accepted;
        synchronized (this) {
            accepted = !stopping.get() && stagedBytes < maxBytes && slots.tryAcquire();
            if (accepted) {
                Batch batch = new Batch(claim, prepare, transientFailure);
                control.execute(() -> {
                    batches.add(batch);
                    batch.heartbeat = control.scheduleAtFixedRate(() -> heartbeat(batch), 30, 30, TimeUnit.SECONDS);
                    submit(batch);
                    pump();
                });
            }
        }
        if (!accepted) {
            transactions.releaseSaturatedBatch(claim);
        }
    }

    public synchronized void cancelExecution(String executionId) {
        if (stopping.get()) {
            return;
        }
        control.execute(() -> {
            batches.stream().filter(batch -> batch.claim.executionId().equals(executionId))
                    .forEach(batch -> fail(batch, batch.currentItem(), new CancellationException("사진 저장 취소")));
            pump();
        });
    }

    private void heartbeat(Batch batch) {
        try (var scope = WorkflowMdcScope.open(batch.context)) {
            try {
                if (!transactions.renewLease(batch.claim)) {
                    throw new CancellationException("사진 저장 선점 만료");
                }
            } catch (RuntimeException failure) {
                fail(batch, batch.currentItem(), failure);
                scheduler.cancelExecution(batch.claim.executionId());
                pump();
            }
        }
    }

    // control 실행기에서만 배치 진행과 저장 순서를 변경한다.
    private void submit(Batch batch) {
        try (var scope = WorkflowMdcScope.open(batch.context)) {
            while (batch.next < batch.claim.photos().size()
                    && batch.claim.photos().get(batch.next).derived() != null) {
                batch.next++;
            }
            if (batch.next == batch.claim.photos().size() || batch.failure != null || stopping.get()) {
                slots.release();
                if (stopping.get()) {
                    fail(batch, batch.currentItem(), new CancellationException("사진 저장 종료"));
                }
                if (batch.active == 0) {
                    complete(batch);
                }
                return;
            }
            Photo photo = batch.claim.photos().get(batch.next++);
            batch.active++;
            batch.converting = true;
            Timer.Sample preparation = Timer.start(meters);
            try {
                if (photo.attempts() >= 3) {
                    throw new IllegalStateException("사진 변환 시도 횟수 초과");
                }
                scheduler.submitPrepared(batch.claim.executionId(), photo.contentType(), () -> {
                    try (var conversionScope = WorkflowMdcScope.open(batch.context)) {
                        populatePhotoLoggingContext(photo);
                        List<String> keys = transactions.startPhotoAttempt(batch.claim, photo.itemId());
                        return WorkflowLog.collect(batch.timings, () -> batch.prepare.apply(photo, keys));
                    }
                }, () -> {
                    try (var leaseScope = WorkflowMdcScope.open(batch.context)) {
                        return !stopping.get() && transactions.renewLease(batch.claim);
                    }
                })
                        .whenComplete((prepared, failure) -> control.execute(() -> {
                            batch.converting = false;
                            record(preparation, "image_prepare", failure == null);
                            if (failure == null) {
                                stagedBytes += prepared.sizeBytes();
                                waiting.addLast(new Pending(batch, photo, prepared));
                            } else {
                                batch.active--;
                                slots.release();
                                fail(batch, photo.itemId(), failure);
                            }
                            pump();
                        }));
            } catch (RuntimeException failure) {
                record(preparation, "image_prepare", false);
                batch.converting = false;
                batch.active--;
                slots.release();
                if (failure instanceof RejectedExecutionException) {
                    batch.saturated = true;
                    batch.next--;
                } else {
                    fail(batch, photo.itemId(), failure);
                }
                if (batch.active == 0) {
                    complete(batch);
                }
            }
        }
    }

    private void pump() {
        if (!storing) {
            Pending pending = waiting.pollFirst();
            while (pending != null && (pending.batch.failure != null || stopping.get())) {
                finish(pending);
                pending = waiting.pollFirst();
            }
            if (pending != null) {
                store(pending);
            }
        }
        for (Batch batch : List.copyOf(batches)) {
            if (batch.active == 0 && (batch.failure != null || batch.saturated || batch.next == batch.claim.photos().size())) {
                complete(batch);
            } else if (!stopping.get() && batch.failure == null && !batch.saturated && !batch.converting
                    && batch.next < batch.claim.photos().size() && stagedBytes < maxBytes && slots.tryAcquire()) {
                submit(batch);
            }
        }
        if (stopping.get() && batches.isEmpty()) {
            drained.complete(null);
            control.shutdown();
        }
    }

    private void store(Pending pending) {
        try (var scope = WorkflowMdcScope.open(pending.batch.context)) {
            storing = true;
            Timer.Sample storingTime = Timer.start(meters);
            CompletableFuture<Void> upload = CompletableFuture.completedFuture(null);
            for (PreparedPhotoDerivative.Output output : pending.prepared.outputs()) {
                upload = upload.thenComposeAsync(ignored -> {
                    try (var uploadScope = WorkflowMdcScope.open(pending.batch.context)) {
                        requireActive(pending.batch);
                        if (pending.uploadTiming == null) {
                            pending.uploadTiming = startDerivativeUploadLogging(pending);
                        }
                        return storage.storeDerivedToAsync(output.objectKey(), output.file(), output.mimeType())
                                .thenApply(key -> null);
                    }
                }, control);
            }
            upload.whenComplete((ignored, failure) -> control.execute(() -> {
                try (var callbackScope = WorkflowMdcScope.open(pending.batch.context)) {
                    logDerivativeUploadCompleted(pending, failure);
                    boolean success = false;
                    try {
                        if (failure != null) {
                            throw new CompletionException(failure);
                        }
                        requireActive(pending.batch);
                        List<String> retained = new ArrayList<>(pending.prepared.outputs().stream()
                                .map(PreparedPhotoDerivative.Output::objectKey).toList());
                        retained.add(pending.photo.objectKey());
                        storage.retain(retained);
                        WorkflowLog.collect(pending.batch.timings, () -> {
                            transactions.savePhotoSuccess(pending.batch.claim, pending.photo.itemId(), pending.prepared.photo());
                            return null;
                        });
                        success = true;
                    } catch (RuntimeException storageFailure) {
                        fail(pending.batch, pending.photo.itemId(), storageFailure);
                    } finally {
                        // 전송 Future를 취소하지 않아 파일 읽기 종료 전에 임시 파일을 지우지 않는다.
                        finish(pending);
                        record(storingTime, "derivative_store", success);
                        storing = false;
                        pump();
                    }
                }
            }));
        }
    }

    private void record(Timer.Sample sample, String stage, boolean success) {
        sample.stop(Timer.builder("yeodam.trip.stage")
                .tags("stage", stage, "outcome", success ? "success" : "failure").register(meters));
    }

    private void requireActive(Batch batch) {
        if (stopping.get() || batch.failure != null || !transactions.renewLease(batch.claim)) {
            throw new CancellationException("종료되거나 선점을 잃은 사진 저장");
        }
    }

    private void finish(Pending pending) {
        pending.prepared.close();
        stagedBytes -= pending.prepared.sizeBytes();
        pending.batch.active--;
        slots.release();
    }

    private void fail(Batch batch, Long itemId, Throwable failure) {
        try (var scope = WorkflowMdcScope.open(batch.context)) {
            if (batch.failure == null) {
                logPhotoStorageFailure(itemId, failure);
                batch.failure = failure;
                batch.failedItem = itemId;
            }
        }
    }

    private void complete(Batch batch) {
        try (var scope = WorkflowMdcScope.open(batch.context)) {
            batch.timings.log();
            batches.remove(batch);
            batch.heartbeat.cancel(false);
            try {
                if (batch.failure == null && batch.saturated) {
                    transactions.releaseSaturatedBatch(batch.claim);
                } else if (batch.failure == null) {
                    transactions.completeBatch(batch.claim);
                } else {
                    transactions.retryBatch(batch.claim, batch.failedItem, !batch.transientFailure.test(batch.failure));
                }
            } catch (RuntimeException failure) {
                logBatchCompletionFailure(batch.claim.executionId(), failure);
                // DB에 남은 claim은 lease 만료 후 기존 복구 경로에서 다시 처리한다.
            }
        }
    }

    @PreDestroy
    synchronized void close() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        control.execute(() -> {
            batches.forEach(batch -> fail(batch, batch.currentItem(), new CancellationException("사진 저장 종료")));
            pump();
        });
        try {
            drained.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException failure) {
            logShutdownWaitFailure(failure);
        }
    }

    private WorkflowLog.Stage startDerivativeUploadLogging(Pending pending) {
        return WorkflowLog.stage("derivative_upload").debug()
                .field("item_id", pending.photo.itemId())
                .recordTo(pending.batch.timings);
    }

    private void logDerivativeUploadCompleted(Pending pending, Throwable failure) {
        if (pending.uploadTiming != null) {
            if (failure == null) {
                pending.uploadTiming.success();
            }
            pending.uploadTiming.close();
        }
    }

    private void logPhotoStorageFailure(Long itemId, Throwable failure) {
        log.atWarn().addKeyValue("event", "photo_storage_retry")
                .addKeyValue("result", "failure").addKeyValue("item_id", itemId)
                .addKeyValue("failure_stage", "image_storage_pipeline")
                .addKeyValue("failure", failure.getClass().getSimpleName())
                .log("사진 파이프라인 처리를 완료하지 못했습니다.");
    }

    private void logBatchCompletionFailure(String executionId, RuntimeException failure) {
        log.warn("사진 배치 완료 기록 재시도: executionId={}, cause={}", executionId,
                failure.getClass().getSimpleName());
    }

    private void logShutdownWaitFailure(Exception failure) {
        log.warn("사진 저장 종료 대기 초과: cause={}", failure.getClass().getSimpleName());
    }

    private void populatePhotoLoggingContext(Photo photo) {
        MDC.put("item_id", photo.itemId().toString());
        MDC.put("attempts", Integer.toString(photo.attempts() + 1));
    }

    private static Map<String, String> captureBatchLoggingContext(InitialAttachmentUploadBatchSnapshot claim) {
        try (var scope = WorkflowMdcScope.job("trip_creation", claim.tripId(), claim.executionId())) {
            MDC.put("batch_no", Integer.toString(claim.batchNo()));
            return MDC.getCopyOfContextMap();
        }
    }

    private static class Batch {
        private final InitialAttachmentUploadBatchSnapshot claim;
        private final Map<String, String> context;
        private final WorkflowLog.BatchTimings timings = new WorkflowLog.BatchTimings();
        private final BiFunction<Photo, List<String>, PreparedPhotoDerivative> prepare;
        private final Predicate<Throwable> transientFailure;
        private int next;
        private int active;
        private boolean converting;
        private boolean saturated;
        private Throwable failure;
        private Long failedItem;
        private ScheduledFuture<?> heartbeat;

        private Batch(
                InitialAttachmentUploadBatchSnapshot claim,
                BiFunction<Photo, List<String>, PreparedPhotoDerivative> prepare,
                Predicate<Throwable> transientFailure
        ) {
            this.claim = claim;
            context = captureBatchLoggingContext(claim);
            this.prepare = prepare;
            this.transientFailure = transientFailure;
        }

        private Long currentItem() {
            return claim.photos().get(Math.max(0, next - 1)).itemId();
        }
    }

    private static final class Pending {
        private final Batch batch;
        private final Photo photo;
        private final PreparedPhotoDerivative prepared;
        private WorkflowLog.Stage uploadTiming;

        private Pending(Batch batch, Photo photo, PreparedPhotoDerivative prepared) {
            this.batch = batch;
            this.photo = photo;
            this.prepared = prepared;
        }
    }
}

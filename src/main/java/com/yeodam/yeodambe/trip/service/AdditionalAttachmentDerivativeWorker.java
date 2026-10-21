package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.service.response.AdditionalAttachmentUploadBatchSnapshot;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdditionalAttachmentDerivativeWorker {
    private final AdditionalAttachmentUploadTransactionService transactions;
    private final TripAttachmentDerivativeService derivatives;
    private final TripDerivativeScheduler scheduler;
    private final TripAttachmentStorageClient storage;
    private final ScheduledThreadPoolExecutor leases = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().name("additional-photo-lease").daemon(true).factory());

    @Scheduled(initialDelayString = "${app.trip.worker-initial-delay-ms:0}", fixedDelayString = "${app.trip.worker-delay-ms:5000}")
    public void processPendingBatches() {
        transactions.claimBatch().ifPresent(claim -> convertNext(claim, 0, new WorkflowLog.BatchTimings()));
    }

    private void convertNext(
            AdditionalAttachmentUploadBatchSnapshot claim,
            int from,
            WorkflowLog.BatchTimings timings
    ) {
        try (var scope = WorkflowMdcScope.job("trip_reanalysis", claim.tripId(), claim.executionId())) {
            populateBatchLoggingContext(claim);
            var context = MDC.getCopyOfContextMap();
            int index = from;
            while (index < claim.photos().size() && claim.photos().get(index).derived() != null) {
                index++;
            }
            if (index == claim.photos().size()) {
                timings.log();
                try {
                    transactions.completeBatch(claim);
                } catch (RuntimeException failure) {
                    logBatchCompletionFailure(claim.executionId(), failure);
                    transactions.releaseSaturatedBatch(claim);
                }
                return;
            }
            var photo = claim.photos().get(index);
            if (photo.attempts() >= 3) {
                timings.log();
                transactions.retryBatch(claim, photo.itemId(), true);
                return;
            }
            var heartbeat = leases.scheduleAtFixedRate(() -> {
                try (var heartbeatScope = WorkflowMdcScope.open(context)) {
                    if (!transactions.renewLease(claim)) {
                        scheduler.cancelExecution(claim.executionId());
                    }
                } catch (RuntimeException failure) {
                    logConversionLeaseFailure(claim, failure);
                    scheduler.cancelExecution(claim.executionId());
                }
            }, 30, 30, TimeUnit.SECONDS);
            try {
                int next = index + 1;
                var task = new TripDerivativeScheduler.PhotoTask(0, photo.contentType(), () -> {
                    try (var conversionScope = WorkflowMdcScope.open(context)) {
                        populatePhotoLoggingContext(photo.itemId(), photo.attempts() + 1);
                        var keys = transactions.startPhotoAttempt(claim, photo.itemId());
                        var derived = WorkflowLog.collect(timings, () -> derivatives.generateAdditional(
                                claim.executionId(), photo.objectKey(), photo.contentType(), keys));
                        var retained = new ArrayList<>(keys);
                        retained.add(photo.objectKey());
                        storage.retain(retained);
                        WorkflowLog.collect(timings, () -> {
                            transactions.savePhotoSuccess(claim, photo.itemId(), derived);
                            return null;
                        });
                        return derived;
                    }
                });
                scheduler.submit(claim.executionId(), List.of(task), () -> transactions.renewLease(claim))
                        .whenComplete((result, failure) -> {
                            try (var callbackScope = WorkflowMdcScope.open(context)) {
                                heartbeat.cancel(false);
                                if (failure == null) {
                                    convertNext(claim, next, timings);
                                } else {
                                    timings.log();
                                    transactions.retryBatch(claim, photo.itemId(),
                                            !derivatives.isTransientConversionFailure(failure));
                                }
                            }
                        });
            } catch (RejectedExecutionException saturated) {
                heartbeat.cancel(false);
                timings.log();
                transactions.releaseSaturatedBatch(claim);
            } catch (RuntimeException failure) {
                heartbeat.cancel(false);
                timings.log();
                transactions.retryBatch(claim, photo.itemId(), !derivatives.isTransientConversionFailure(failure));
            }
        }
    }

    @PreDestroy
    void shutdown() {
        leases.shutdownNow();
    }

    private void logConversionLeaseFailure(
            AdditionalAttachmentUploadBatchSnapshot claim, RuntimeException failure
    ) {
        log.atWarn().addKeyValue("job_id", claim.executionId())
                .addKeyValue("trip_id", claim.tripId())
                .addKeyValue("failure_stage", "conversion_lease")
                .addKeyValue("failure", failure.getClass().getSimpleName())
                .log("추가 사진 변환 선점 갱신에 실패했습니다.");
    }

    private void logBatchCompletionFailure(String executionId, RuntimeException failure) {
        log.warn("추가 사진 배치 저장 실패: executionId={}, cause={}",
                executionId, failure.getClass().getSimpleName());
    }

    private void populateBatchLoggingContext(AdditionalAttachmentUploadBatchSnapshot claim) {
        if (claim.requestId() != null && !claim.requestId().equals(claim.executionId())) {
            MDC.put("request_id", claim.requestId());
        }
        MDC.put("batch_id", claim.batchId().toString());
    }

    private void populatePhotoLoggingContext(Long itemId, int attempts) {
        MDC.put("item_id", itemId.toString());
        MDC.put("attempts", Integer.toString(attempts));
    }
}

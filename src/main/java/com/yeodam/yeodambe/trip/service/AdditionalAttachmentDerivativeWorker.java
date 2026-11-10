package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionRoute;
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
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdditionalAttachmentDerivativeWorker {
    private final AdditionalAttachmentUploadTransactionService transactions;
    private final TripMutationCoordinator mutations;
    private final TripAttachmentDerivativeService derivatives;
    private final TripDerivativeScheduler scheduler;
    private final TripAttachmentStorageClient storage;
    private final AttachmentConversionProperties conversionProperties;
    private final ScheduledThreadPoolExecutor leases = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().name("additional-photo-lease").daemon(true).factory());

    @Scheduled(initialDelayString = "${app.trip.worker-initial-delay-ms:0}", fixedDelayString = "${app.trip.worker-delay-ms:5000}")
    public void processPendingBatches() {
        if (conversionProperties.mode() != AttachmentConversionRoute.DB) {
            return;
        }
        for (AdditionalAttachmentUploadBatch candidate : transactions.findPendingBatches()) {
            Optional<AdditionalAttachmentUploadBatchSnapshot> claim = mutations.execute(candidate.getTripId(),
                    () -> transactions.claimBatch(candidate.getId()));
            if (claim.isPresent()) {
                convertNext(claim.get(), 0, new WorkflowLog.BatchTimings());
                return;
            }
        }
    }

    private void convertNext(
            AdditionalAttachmentUploadBatchSnapshot claim,
            int from,
            WorkflowLog.BatchTimings timings
    ) {
        try (WorkflowMdcScope scope = WorkflowMdcScope.job("trip_reanalysis", claim.tripId(), claim.executionId())) {
            populateBatchLoggingContext(claim);
            Map<String, String> context = MDC.getCopyOfContextMap();
            int index = from;
            while (index < claim.photos().size() && claim.photos().get(index).derived() != null) {
                index++;
            }
            if (index == claim.photos().size()) {
                timings.log();
                try {
                    mutations.run(claim.tripId(), () -> transactions.completeBatch(claim));
                } catch (RuntimeException failure) {
                    logBatchCompletionFailure(claim.executionId(), failure);
                    mutations.run(claim.tripId(), () -> transactions.releaseSaturatedBatch(claim));
                }
                return;
            }
            AdditionalAttachmentUploadBatchSnapshot.Photo photo = claim.photos().get(index);
            if (photo.attempts() >= 3) {
                timings.log();
                mutations.run(claim.tripId(), () -> transactions.retryBatch(claim, photo.itemId(), true));
                return;
            }
            ScheduledFuture<?> heartbeat = leases.scheduleAtFixedRate(() -> {
                try (WorkflowMdcScope heartbeatScope = WorkflowMdcScope.open(context)) {
                    if (!mutations.execute(claim.tripId(), () -> transactions.renewLease(claim))) {
                        scheduler.cancelExecution(claim.executionId());
                    }
                } catch (RuntimeException failure) {
                    logConversionLeaseFailure(claim, failure);
                    scheduler.cancelExecution(claim.executionId());
                }
            }, 30, 30, TimeUnit.SECONDS);
            try {
                int next = index + 1;
                TripDerivativeScheduler.PhotoTask task = new TripDerivativeScheduler.PhotoTask(0, photo.contentType(), () -> {
                    try (WorkflowMdcScope conversionScope = WorkflowMdcScope.open(context)) {
                        populatePhotoLoggingContext(photo.itemId(), photo.attempts() + 1);
                        List<String> keys = mutations.execute(claim.tripId(),
                                () -> transactions.startPhotoAttempt(claim, photo.itemId()));
                        DerivedPhotoKeys derived = WorkflowLog.collect(timings, () -> derivatives.generateAdditional(
                                claim.executionId(), photo.objectKey(), photo.contentType(), keys));
                        List<String> retained = new ArrayList<>(keys);
                        retained.add(photo.objectKey());
                        storage.retain(retained);
                        return derived;
                    }
                });
                scheduler.submit(claim.executionId(), List.of(task),
                                () -> mutations.execute(claim.tripId(), () -> transactions.renewLease(claim)))
                        .whenComplete((result, failure) -> {
                            try (WorkflowMdcScope callbackScope = WorkflowMdcScope.open(context)) {
                                if (failure == null) {
                                    saveConverted(claim, photo, result.getFirst(), next, timings, heartbeat, context);
                                } else {
                                    heartbeat.cancel(false);
                                    timings.log();
                                    mutations.run(claim.tripId(), () -> transactions.retryBatch(claim, photo.itemId(),
                                            !derivatives.isTransientConversionFailure(failure)));
                                }
                            }
                        });
            } catch (RejectedExecutionException saturated) {
                heartbeat.cancel(false);
                timings.log();
                mutations.run(claim.tripId(), () -> transactions.releaseSaturatedBatch(claim));
            } catch (RuntimeException failure) {
                heartbeat.cancel(false);
                timings.log();
                mutations.run(claim.tripId(), () -> transactions.retryBatch(
                        claim, photo.itemId(), !derivatives.isTransientConversionFailure(failure)));
            }
        }
    }

    private void saveConverted(
            AdditionalAttachmentUploadBatchSnapshot claim,
            AdditionalAttachmentUploadBatchSnapshot.Photo photo,
            DerivedPhotoKeys output,
            int next,
            WorkflowLog.BatchTimings timings,
            ScheduledFuture<?> heartbeat,
            Map<String, String> context
    ) {
        try (WorkflowMdcScope scope = WorkflowMdcScope.open(context)) {
            try {
                WorkflowLog.collect(timings, () -> {
                    mutations.run(claim.tripId(), () -> transactions.savePhotoSuccess(claim, photo.itemId(), output));
                    return null;
                });
            } catch (TransientDataAccessException
                     | RecoverableDataAccessException
                     | DataAccessResourceFailureException
                     | TripEditLockRejectedException failure) {
                logBatchCompletionFailure(claim.executionId(), failure);
                if (!leases.isShutdown()) {
                    leases.schedule(() -> saveConverted(claim, photo, output, next, timings, heartbeat, context),
                            1, TimeUnit.SECONDS);
                }
                return;
            } catch (RuntimeException failure) {
                heartbeat.cancel(false);
                timings.log();
                boolean permanent = failure instanceof DataAccessException
                        || !derivatives.isTransientConversionFailure(failure);
                mutations.run(claim.tripId(), () -> transactions.retryBatch(claim, photo.itemId(), permanent));
                return;
            }
            heartbeat.cancel(false);
            convertNext(claim, next, timings);
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

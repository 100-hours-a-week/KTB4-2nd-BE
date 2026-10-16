package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.service.response.AdditionalAttachmentUploadBatchSnapshot;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        transactions.claimBatch().ifPresent(claim -> convertNext(claim, 0));
    }

    private void convertNext(AdditionalAttachmentUploadBatchSnapshot claim, int from) {
        int index = from;
        while (index < claim.photos().size() && claim.photos().get(index).derived() != null) index++;
        if (index == claim.photos().size()) {
            try { transactions.completeBatch(claim); }
            catch (RuntimeException failure) {
                log.warn("Additional batch persistence failed: executionId={}", claim.executionId(), failure);
                transactions.releaseSaturatedBatch(claim);
            }
            return;
        }
        var photo = claim.photos().get(index);
        if (photo.attempts() >= 3) {
            transactions.retryBatch(claim, photo.itemId(), true);
            return;
        }
        var heartbeat = leases.scheduleAtFixedRate(() -> {
            try {
                if (!transactions.renewLease(claim)) scheduler.cancelExecution(claim.executionId());
            } catch (RuntimeException failure) {
                log.warn("Additional conversion lease failed: executionId={}", claim.executionId(), failure);
                scheduler.cancelExecution(claim.executionId());
            }
        }, 30, 30, TimeUnit.SECONDS);
        try {
            int next = index + 1;
            var task = new TripDerivativeScheduler.PhotoTask(0, photo.contentType(), () -> {
                var previous = org.slf4j.MDC.getCopyOfContextMap();
                try {
                    org.slf4j.MDC.put("request_id", claim.requestId());
                    var keys = transactions.startPhotoAttempt(claim, photo.itemId());
                    var derived = derivatives.generateAdditional(claim.executionId(), photo.objectKey(), photo.contentType(), keys);
                    var retained = new ArrayList<>(keys);
                    retained.add(photo.objectKey());
                    storage.retain(retained);
                    transactions.savePhotoSuccess(claim, photo.itemId(), derived);
                    return derived;
                } finally {
                    if (previous == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previous);
                }
            });
            scheduler.submit(claim.executionId(), List.of(task), () -> transactions.renewLease(claim))
                    .whenComplete((result, failure) -> {
                        heartbeat.cancel(false);
                        if (failure == null) convertNext(claim, next);
                        else transactions.retryBatch(claim, photo.itemId(), !derivatives.isTransientConversionFailure(failure));
                    });
        } catch (RejectedExecutionException saturated) {
            heartbeat.cancel(false);
            transactions.releaseSaturatedBatch(claim);
        } catch (RuntimeException failure) {
            heartbeat.cancel(false);
            transactions.retryBatch(claim, photo.itemId(), !derivatives.isTransientConversionFailure(failure));
        }
    }

    @PreDestroy
    void shutdown() { leases.shutdownNow(); }
}

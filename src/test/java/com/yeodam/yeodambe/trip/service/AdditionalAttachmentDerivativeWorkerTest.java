package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.service.response.AdditionalAttachmentUploadBatchSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AdditionalAttachmentDerivativeWorkerTest {
    private final AdditionalAttachmentUploadTransactionService transactions = mock(AdditionalAttachmentUploadTransactionService.class);
    private final TripAttachmentDerivativeService derivatives = mock(TripAttachmentDerivativeService.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final DerivedPhotoKeys derived = new DerivedPhotoKeys("original", "analyze", "preview", null,
            null, null, null, null, 12L, 8L, 4L, null);

    private AdditionalAttachmentUploadBatchSnapshot claim() {
        return new AdditionalAttachmentUploadBatchSnapshot(1L, "upload", "execution", 7L, 2L, "token", "request", List.of(
                new AdditionalAttachmentUploadBatchSnapshot.Photo(1L, "saved.jpg", "image/jpeg", 12L, "saved", 1, null, derived),
                new AdditionalAttachmentUploadBatchSnapshot.Photo(2L, "new.jpg", "image/jpeg", 12L, "original", 0, null, null)));
    }

    @Test
    void convertsOnlyUnsavedPhotoAndCompletesBatchAfterPhotoPersistence() throws Exception {
        var claim = claim();
        when(transactions.claimBatch()).thenReturn(Optional.of(claim));
        when(transactions.renewLease(claim)).thenReturn(true);
        when(transactions.startPhotoAttempt(claim, 2L)).thenReturn(List.of("analyze", "preview"));
        when(derivatives.generateAdditional("execution", "original", "image/jpeg", List.of("analyze", "preview"))).thenReturn(derived);
        var completed = new CountDownLatch(1);
        doAnswer(call -> { completed.countDown(); return null; }).when(transactions).completeBatch(claim);
        var scheduler = new TripDerivativeScheduler(1, 1, 1, 1, new SimpleMeterRegistry());
        var worker = new AdditionalAttachmentDerivativeWorker(transactions, derivatives, scheduler, storage);
        try {
            worker.processPendingBatches();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            verify(transactions).savePhotoSuccess(claim, 2L, derived);
            verify(transactions, never()).startPhotoAttempt(claim, 1L);
            verify(storage).retain(List.of("analyze", "preview", "original"));
        } finally { worker.shutdown(); scheduler.stop(5, TimeUnit.SECONDS); }
    }

    @Test
    void conversionFailurePreservesJobContextAndRestoresCaller() throws Exception {
        var claim = claim();
        when(transactions.claimBatch()).thenReturn(Optional.of(claim));
        when(transactions.renewLease(claim)).thenReturn(true);
        when(transactions.startPhotoAttempt(claim, 2L)).thenReturn(List.of("analyze", "preview"));
        AtomicReference<Map<String, String>> conversionContext = new AtomicReference<>();
        AtomicReference<Map<String, String>> retryContext = new AtomicReference<>();
        when(derivatives.generateAdditional(anyString(), anyString(), anyString(), anyList()))
                .thenAnswer(call -> {
                    conversionContext.set(MDC.getCopyOfContextMap());
                    throw new IllegalStateException("conversion failed");
                });
        CountDownLatch retried = new CountDownLatch(1);
        doAnswer(call -> {
            retryContext.set(MDC.getCopyOfContextMap());
            retried.countDown();
            return null;
        }).when(transactions).retryBatch(eq(claim), eq(2L), anyBoolean());
        var meters = new SimpleMeterRegistry();
        var scheduler = new TripDerivativeScheduler(1, 1, 1, 1, meters);
        var worker = new AdditionalAttachmentDerivativeWorker(transactions, derivatives, scheduler, storage);
        MDC.put("request_id", "caller");
        try {
            worker.processPendingBatches();
            assertThat(retried.await(5, TimeUnit.SECONDS)).isTrue();
            Map<String, String> expected = Map.of(
                    "workflow", "trip_reanalysis",
                    "trip_id", "7",
                    "job_id", "execution",
                    "request_id", "request",
                    "batch_id", "1"
            );
            assertThat(conversionContext.get()).containsAllEntriesOf(expected)
                    .containsEntry("item_id", "2")
                    .containsEntry("attempts", "1");
            assertThat(retryContext.get()).containsAllEntriesOf(expected);
            assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("request_id", "caller"));
            verify(transactions, never()).savePhotoSuccess(any(), anyLong(), any());
        } finally {
            worker.shutdown();
            scheduler.stop(5, TimeUnit.SECONDS);
            meters.close();
            MDC.clear();
        }
    }

    @Test
    void admissionRejectionDoesNotStartPhotoConversion() {
        var claim = claim();
        when(transactions.claimBatch()).thenReturn(Optional.of(claim));
        var scheduler = mock(TripDerivativeScheduler.class);
        when(scheduler.submit(anyString(), anyList(), any())).thenThrow(new RejectedExecutionException());
        var worker = new AdditionalAttachmentDerivativeWorker(transactions, derivatives, scheduler, storage);
        try {
            worker.processPendingBatches();
            verify(transactions).releaseSaturatedBatch(claim);
            verify(transactions, never()).startPhotoAttempt(any(), anyLong());
            verifyNoInteractions(derivatives, storage);
        } finally { worker.shutdown(); }
    }
}

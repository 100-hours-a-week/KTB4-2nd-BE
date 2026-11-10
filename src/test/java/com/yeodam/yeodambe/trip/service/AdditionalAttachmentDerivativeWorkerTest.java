package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.trip.fixture.TripMutationTestFixture;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionRoute;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.fixture.TripUploadTestErrorMessage;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;

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

    @ParameterizedTest
    @ValueSource(strings = {"HOLD", "KAFKA"})
    void HOLD나_Kafka_모드는_기존_작업_제출을_시작하지_않는다(String mode) {
        AttachmentConversionProperties.Admission admission = new AttachmentConversionProperties.Admission(
                Map.of(Lane.LIGHT, 100L,
                        Lane.HEIC, 100L),
                Map.of(Lane.LIGHT, 100000L,
                        Lane.HEIC, 100000L),
                Duration.ofHours(1), Duration.ofSeconds(30), Duration.ofSeconds(10), 0.5);
        AdditionalAttachmentDerivativeWorker worker = new AdditionalAttachmentDerivativeWorker(
                transactions,
                TripMutationTestFixture.direct(),
                derivatives,
                mock(TripDerivativeScheduler.class),
                storage,
                new AttachmentConversionProperties(
                        AttachmentConversionRoute.valueOf(mode),
                        admission,
                        null,
                        null,
                        new AttachmentConversionProperties.Recovery(Duration.ofMinutes(5), 10, 2, 10),
                        true,
                        true,
                        true
                )
        );
        try {
            worker.processPendingBatches();
            verifyNoInteractions(transactions, derivatives, storage);
        } finally {
            worker.shutdown();
        }
    }

    private void stubClaim(AdditionalAttachmentUploadBatchSnapshot claim) {
        AdditionalAttachmentUploadBatch candidate = mock(AdditionalAttachmentUploadBatch.class);
        when(candidate.getId()).thenReturn(claim.batchId());
        when(candidate.getTripId()).thenReturn(claim.tripId());
        when(transactions.findPendingBatches()).thenReturn(List.of(candidate));
        when(transactions.claimBatch(claim.batchId())).thenReturn(Optional.of(claim));
    }

    private AdditionalAttachmentUploadBatchSnapshot claim() {
        return new AdditionalAttachmentUploadBatchSnapshot(1L, "upload", "execution", 7L, 2L, "token", "request", List.of(
                new AdditionalAttachmentUploadBatchSnapshot.Photo(1L, "saved.jpg", "image/jpeg", 12L, "saved", 1, null, derived),
                new AdditionalAttachmentUploadBatchSnapshot.Photo(2L, "new.jpg", "image/jpeg", 12L, "original", 0, null, null)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 변환한_사진은_DB나_Redis_저장이_잠시_실패해도_다시_변환하지_않는다(boolean redisUnavailable) throws Exception {
        AdditionalAttachmentUploadBatchSnapshot claim = claim();
        stubClaim(claim);
        when(transactions.renewLease(claim)).thenReturn(true);
        when(transactions.startPhotoAttempt(claim, 2L)).thenReturn(List.of("analyze", "preview"));
        when(derivatives.generateAdditional("execution", "original", "image/jpeg", List.of("analyze", "preview")))
                .thenReturn(derived);
        RuntimeException failure = redisUnavailable
                ? new TripEditLockRejectedException(TripEditLockRejectedException.Reason.STORE_UNAVAILABLE)
                : new DataAccessResourceFailureException(TripUploadTestErrorMessage.DATABASE_UNAVAILABLE.message());
        doThrow(failure).doNothing().when(transactions).savePhotoSuccess(claim, 2L, derived);
        CountDownLatch completed = new CountDownLatch(1);
        doAnswer(invocation -> {
            completed.countDown();
            return null;
        }).when(transactions).completeBatch(claim);
        TripDerivativeScheduler scheduler = new TripDerivativeScheduler(1, 1, 1, 1, new SimpleMeterRegistry());
        AdditionalAttachmentDerivativeWorker worker = new AdditionalAttachmentDerivativeWorker(
                transactions,
                TripMutationTestFixture.direct(),
                derivatives,
                scheduler,
                storage,
                new AttachmentConversionProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        true,
                        true
                )
        );
        try {
            worker.processPendingBatches();
            assertThat(completed.await(8, TimeUnit.SECONDS)).isTrue();
            verify(transactions, times(2)).savePhotoSuccess(claim, 2L, derived);
            verify(transactions).startPhotoAttempt(claim, 2L);
            verify(derivatives).generateAdditional("execution", "original", "image/jpeg", List.of("analyze", "preview"));
            verify(transactions, never()).retryBatch(any(), anyLong(), anyBoolean());
        } finally {
            worker.shutdown();
            scheduler.stop(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 영구_DB_오류나_임대_상실은_저장을_반복하지_않는다(boolean leaseLost) throws Exception {
        AdditionalAttachmentUploadBatchSnapshot claim = claim();
        stubClaim(claim);
        when(transactions.renewLease(claim)).thenReturn(true);
        when(transactions.startPhotoAttempt(claim, 2L)).thenReturn(List.of("analyze", "preview"));
        when(derivatives.generateAdditional("execution", "original", "image/jpeg", List.of("analyze", "preview")))
                .thenReturn(derived);
        RuntimeException failure = leaseLost
                ? new TripAttachmentAddNotAllowedException()
                : new DataIntegrityViolationException(TripUploadTestErrorMessage.DATABASE_WRITE_CONSTRAINT_FAILED.message());
        when(derivatives.isTransientConversionFailure(failure)).thenReturn(!leaseLost);
        if (leaseLost) {
            doThrow(new DataAccessResourceFailureException(TripUploadTestErrorMessage.DATABASE_UNAVAILABLE.message()))
                    .doThrow(failure).when(transactions).savePhotoSuccess(claim, 2L, derived);
        } else {
            doThrow(failure).when(transactions).savePhotoSuccess(claim, 2L, derived);
        }
        CountDownLatch stopped = new CountDownLatch(1);
        doAnswer(invocation -> {
            stopped.countDown();
            return null;
        }).when(transactions).retryBatch(claim, 2L, true);
        TripDerivativeScheduler scheduler = new TripDerivativeScheduler(1, 1, 1, 1, new SimpleMeterRegistry());
        AdditionalAttachmentDerivativeWorker worker = new AdditionalAttachmentDerivativeWorker(
                transactions,
                TripMutationTestFixture.direct(),
                derivatives,
                scheduler,
                storage,
                new AttachmentConversionProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        true,
                        true
                )
        );
        try {
            worker.processPendingBatches();
            assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
            verify(transactions, times(leaseLost ? 2 : 1)).savePhotoSuccess(claim, 2L, derived);
            verify(transactions).startPhotoAttempt(claim, 2L);
            verify(derivatives).generateAdditional("execution", "original", "image/jpeg", List.of("analyze", "preview"));
            verify(transactions, never()).completeBatch(any());
        } finally {
            worker.shutdown();
            scheduler.stop(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 미저장_사진만_변환하고_사진을_영속화한_뒤_배치를_완료한다() throws Exception {
        AdditionalAttachmentUploadBatchSnapshot claim = claim();
        stubClaim(claim);
        when(transactions.renewLease(claim)).thenReturn(true);
        when(transactions.startPhotoAttempt(claim, 2L)).thenReturn(List.of("analyze", "preview"));
        when(derivatives.generateAdditional("execution", "original", "image/jpeg", List.of("analyze", "preview"))).thenReturn(derived);
        CountDownLatch completed = new CountDownLatch(1);
        doAnswer(call -> {
            completed.countDown();
            return null;
        }).when(transactions).completeBatch(claim);
        TripDerivativeScheduler scheduler = new TripDerivativeScheduler(1, 1, 1, 1, new SimpleMeterRegistry());
        AdditionalAttachmentDerivativeWorker worker = new AdditionalAttachmentDerivativeWorker(
                transactions,
                TripMutationTestFixture.direct(),
                derivatives,
                scheduler,
                storage,
                new AttachmentConversionProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        true,
                        true
                )
        );
        try {
            worker.processPendingBatches();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            verify(transactions).savePhotoSuccess(claim, 2L, derived);
            verify(transactions, never()).startPhotoAttempt(claim, 1L);
            verify(storage).retain(List.of("analyze", "preview", "original"));
        } finally {
            worker.shutdown();
            scheduler.stop(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 변환_실패는_작업_문맥을_유지하고_호출자_문맥을_복원한다() throws Exception {
        AdditionalAttachmentUploadBatchSnapshot claim = claim();
        stubClaim(claim);
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
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TripDerivativeScheduler scheduler = new TripDerivativeScheduler(1, 1, 1, 1, meters);
        AdditionalAttachmentDerivativeWorker worker = new AdditionalAttachmentDerivativeWorker(
                transactions,
                TripMutationTestFixture.direct(),
                derivatives,
                scheduler,
                storage,
                new AttachmentConversionProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        true,
                        true
                )
        );
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
            verify(transactions, never()).savePhotoSuccess(any(AdditionalAttachmentUploadBatchSnapshot.class), anyLong(), any());
        } finally {
            worker.shutdown();
            scheduler.stop(5, TimeUnit.SECONDS);
            meters.close();
            MDC.clear();
        }
    }

    @Test
    void 진입이_거부되면_사진_변환을_시작하지_않는다() {
        AdditionalAttachmentUploadBatchSnapshot claim = claim();
        stubClaim(claim);
        TripDerivativeScheduler scheduler = mock(TripDerivativeScheduler.class);
        when(scheduler.submit(anyString(), anyList(), any())).thenThrow(new RejectedExecutionException());
        AdditionalAttachmentDerivativeWorker worker = new AdditionalAttachmentDerivativeWorker(
                transactions,
                TripMutationTestFixture.direct(),
                derivatives,
                scheduler,
                storage,
                new AttachmentConversionProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        true,
                        true
                )
        );
        try {
            worker.processPendingBatches();
            verify(transactions).releaseSaturatedBatch(claim);
            verify(transactions, never()).startPhotoAttempt(any(AdditionalAttachmentUploadBatchSnapshot.class), anyLong());
            verifyNoInteractions(derivatives, storage);
        } finally {
            worker.shutdown();
        }
    }
}

package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TripUploadTimeoutServiceTest {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-02T00:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final InitialUploadExecutionRegistry registry = new InitialUploadExecutionRegistry(clock);
    private final TripRepository trips = mock(TripRepository.class);
    private final org.springframework.transaction.support.TransactionOperations tx = new TransactionTemplate() {
        public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
            return action.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        }
    };
    private final TripUploadTimeoutService service = new TripUploadTimeoutService(registry, trips, tx, clock);

    @Test
    void 중간_배치_완료_10분_후_상태만_FAILED로_바꾸고_예약을_해제한다() {
        String id = waiting(7L);
        when(trips.findById(7L)).thenReturn(Optional.of(new Trip(1L, "여행", LocalDate.now(), LocalDate.now())));
        now.set(now.get().plusSeconds(599));
        service.failExpiredUploads();
        verifyNoInteractions(trips);
        assertTrue(registry.isCurrent(7L, id));
        now.set(now.get().plusSeconds(1));
        service.failExpiredUploads();
        verify(trips).finishInitialUpload(7L, 1L, ProcessingStatus.PROCESSING, ProcessingStatus.FAILED);
        assertFalse(registry.isCurrent(7L, id));
        verifyNoMoreInteractionsAfterRead();
    }

    @Test
    void 다음_배치가_접수되면_변환중에는_만료되지_않고_다음_204부터_다시_계산한다() {
        var first = registry.reserveBatch(7L, 1, 3);
        registry.completeBatch(7L, first.executionId(), 1, 1, List.of(photo("one")), false);
        now.set(now.get().plusSeconds(590));
        registry.reserveBatch(7L, 2, 3);
        now.set(now.get().plusSeconds(1000));
        service.failExpiredUploads();
        verifyNoInteractions(trips);
        registry.completeBatch(7L, first.executionId(), 2, 1, List.of(photo("two")), false);
        now.set(now.get().plusSeconds(599));
        service.failExpiredUploads();
        verifyNoInteractions(trips);
        when(trips.findById(7L)).thenReturn(Optional.of(new Trip(1L, "여행", LocalDate.now(), LocalDate.now())));
        now.set(now.get().plusSeconds(1));
        service.failExpiredUploads();
        verify(trips).finishInitialUpload(7L, 1L, ProcessingStatus.PROCESSING, ProcessingStatus.FAILED);
    }

    @Test
    void 최종_배치와_AI_처리는_10분_만료_대상이_아니다() {
        var one = registry.reserveBatch(7L, 1, 1);
        registry.completeBatch(7L, one.executionId(), 1, 1, List.of(photo("one")), true);
        registry.markAnalysisStarted(7L, one.executionId());
        var two = registry.reserveBatch(8L, 1, 1);
        registry.completeBatch(8L, two.executionId(), 1, 1, List.of(photo("two")), true);
        now.set(now.get().plusSeconds(1000));
        service.failExpiredUploads();
        verifyNoInteractions(trips);
    }

    @Test
    void DB_실패시_만료_예약을_유지해_재시도하고_다른_여행은_처리한다() {
        String failed = waiting(7L);
        waiting(8L);
        when(trips.findById(7L)).thenThrow(new IllegalStateException("DB"));
        when(trips.findById(8L)).thenReturn(Optional.of(new Trip(1L, "여행", LocalDate.now(), LocalDate.now())));
        now.set(now.get().plusSeconds(600));
        assertDoesNotThrow(service::failExpiredUploads);
        assertTrue(registry.isCurrent(7L, failed));
        verify(trips).finishInitialUpload(8L, 1L, ProcessingStatus.PROCESSING, ProcessingStatus.FAILED);
    }

    @Test
    void 만료_처리가_시작되면_다음_배치는_DB_반영_후_거절한다() throws Exception {
        waiting(7L);
        now.set(now.get().plusSeconds(600));
        CountDownLatch updating = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch reserving = new CountDownLatch(1);
        when(trips.findById(7L)).thenReturn(Optional.of(new Trip(1L, "여행", LocalDate.now(), LocalDate.now())));
        when(trips.finishInitialUpload(7L, 1L, ProcessingStatus.PROCESSING, ProcessingStatus.FAILED))
                .thenAnswer(call -> {
                    updating.countDown();
                    assertTrue(commit.await(5, TimeUnit.SECONDS));
                    return 1;
                });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var expiry = executor.submit(service::failExpiredUploads);
            try {
                assertTrue(updating.await(5, TimeUnit.SECONDS));
                var next = executor.submit(() -> {
                    reserving.countDown();
                    return registry.reserveBatch(7L, 2, 2);
                });
                assertTrue(reserving.await(5, TimeUnit.SECONDS));
                commit.countDown();
                expiry.get(5, TimeUnit.SECONDS);
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> next.get(5, TimeUnit.SECONDS));
                assertInstanceOf(TripInitialAttachmentUploadNotAllowedException.class, failure.getCause());
            } finally {
                commit.countDown();
            }
        }
    }

    @Test
    void DB_반영_후_커밋이_실패해도_예약을_유지하고_재시도한다() {
        String id = waiting(7L);
        now.set(now.get().plusSeconds(600));
        when(trips.findById(7L)).thenReturn(Optional.of(new Trip(1L, "여행", LocalDate.now(), LocalDate.now())));
        var transaction = new TransactionTemplate() {
            private boolean first = true;
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                T result = action.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
                if (first) {
                    first = false;
                    throw new IllegalStateException("commit failed");
                }
                return result;
            }
        };
        var expiry = new TripUploadTimeoutService(registry, trips, transaction, clock);
        expiry.failExpiredUploads();
        assertTrue(registry.isCurrent(7L, id));
        expiry.failExpiredUploads();
        assertFalse(registry.isCurrent(7L, id));
        verify(trips, times(2)).finishInitialUpload(7L, 1L, ProcessingStatus.PROCESSING, ProcessingStatus.FAILED);
    }

    private String waiting(Long tripId) {
        var batch = registry.reserveBatch(tripId, 1, 2);
        registry.completeBatch(tripId, batch.executionId(), 1, 1, List.of(photo("photo")), false);
        return batch.executionId();
    }
    private static InitialUploadExecutionRegistry.StoredPhoto photo(String key) {
        return new InitialUploadExecutionRegistry.StoredPhoto(
                StoredFile.uploaded(1L, key, key, "image/jpeg"), TripAttachment.initial(7L, 1L, "analyze", "preview"),
                new DerivedPhotoKeys(key, "analyze", "preview"));
    }
    private void verifyNoMoreInteractionsAfterRead() {
        verify(trips).findById(7L);
        verifyNoMoreInteractions(trips);
    }
}

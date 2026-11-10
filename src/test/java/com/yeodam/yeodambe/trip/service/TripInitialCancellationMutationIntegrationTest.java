package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripProcessingCannotBeCanceledException;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.entity.TripCreationOutbox;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.repository.TripCreationOutboxRepository;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.invocation.InvocationOnMock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {"app.trip.edit-lock.enabled=true", "app.trip.edit-lock.wait-time=100ms"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripInitialCancellationMutationIntegrationTest {
    @Autowired
    private TripProcessingCancellationService cancellation;
    @Autowired
    private TripCreationOutboxRepository outboxes;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TripEditLockProperties properties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;
    @MockitoSpyBean
    private InitialAttachmentUploadTransactionService transactions;
    @MockitoBean
    private TripPhotoAnalysisService analysis;
    @MockitoBean
    private TripAttachmentDerivativeService derivatives;

    private Long tripId;
    private Long ownerId;
    private Long foreignId;
    private final AtomicInteger commits = new AtomicInteger();

    @BeforeEach
    void prepareTrip() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        foreignId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        jdbc.update("UPDATE trips SET processing_status = 'PROCESSING' WHERE trip_id = ?", tripId);
        commits.set(0);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void 취소와_전달_확인은_커밋까지_락을_유지하고_외부_호출은_밖에서_실행한다(Mutation mutation) {
        String executionId = prepareQueuedAnalysis();
        if (mutation == Mutation.CANCEL) {
            doAnswer(this::observeMutation).when(transactions).cancelExecution(anyLong(), anyLong());
        } else {
            doAnswer(this::observeMutation).when(transactions).recordCancellationDelivered(anyString());
        }
        doAnswer(call -> {
            assertOutsideMutation();
            return null;
        }).when(analysis).cancel(tripId);
        doAnswer(call -> {
            assertOutsideMutation();
            return null;
        }).when(derivatives).cancelExecution(executionId);

        cancellation.cancel(tripId, ownerId);

        assertThat(commits).hasValue(1);
        assertThat(status()).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject(
                "SELECT cancel_pending FROM trip_creation_executions WHERE execution_id = ?",
                Boolean.class, executionId)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?",
                Long.class, ownerId)).isZero();
        verify(analysis).cancel(tripId);
        verify(derivatives).cancelExecution(executionId);
        assertOutsideMutation();
    }

    @Test
    void 소유자도_여행_락이_경합하면_취소를_접수하지_않는다() {
        assertRejectedWhileLocked(ownerId, TripEditLockRejectedException.class);
    }

    @Test
    void 타인의_취소는_여행_락이_경합해도_기존_404_접근_거절을_유지한다() {
        assertRejectedWhileLocked(foreignId, TripNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessingStatus.class, names = {"CANCELED", "COMPLETED"})
    void 취소됐거나_완료된_여행은_경합_중에도_기존_409를_유지한다(ProcessingStatus status) {
        LocalDateTime deletedAt = status == ProcessingStatus.CANCELED ? LocalDateTime.now() : null;
        jdbc.update("UPDATE trips SET processing_status = ?, deleted_at = ? WHERE trip_id = ?",
                status.name(), deletedAt, tripId);

        assertRejectedWhileLocked(ownerId, TripProcessingCannotBeCanceledException.class);
    }

    @Test
    void 삭제된_처리중_여행은_경합_중에도_기존_404를_유지한다() {
        jdbc.update("UPDATE trips SET deleted_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", tripId);

        assertRejectedWhileLocked(ownerId, TripNotFoundException.class);
    }

    @Test
    void AI_취소_확인_저장이_실패해도_취소와_재전달_의도를_보존한다() {
        String executionId = prepareQueuedAnalysis();
        doThrow(new TripEditLockRejectedException(TripEditLockRejectedException.Reason.STORE_UNAVAILABLE))
                .when(transactions).recordCancellationDelivered(executionId);

        cancellation.cancel(tripId, ownerId);

        assertThat(status()).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject(
                "SELECT cancel_pending FROM trip_creation_executions WHERE execution_id = ?",
                Boolean.class, executionId)).isTrue();
        verify(analysis).cancel(tripId);
        assertOutsideMutation();
    }

    private void assertRejectedWhileLocked(Long requesterId, Class<? extends RuntimeException> expected) {
        String initialStatus = status();
        RLock lock = lock();
        lock.lock();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> response = executor.submit(() -> cancellation.cancel(tripId, requesterId));
            assertThatThrownBy(() -> response.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(expected);
        } finally {
            lock.unlock();
        }
        assertThat(status()).isEqualTo(initialStatus);
        verifyNoInteractions(analysis, derivatives);
    }

    private String prepareQueuedAnalysis() {
        InitialAttachmentUploadBatchSnapshot batch = transactions.prepareBatch(
                tripId, ownerId, new InitialAttachmentUploadUrlRequest(1, 1, true, List.of(
                        new InitialAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 12L)
                ))
        );
        transactions.startProcessing(tripId, ownerId, batch.uploadId());
        InitialAttachmentUploadBatchSnapshot claim = transactions.claimBatch(batch.batchId()).orElseThrow();
        for (InitialAttachmentUploadBatchSnapshot.Photo photo : claim.photos()) {
            List<String> keys = transactions.startPhotoAttempt(claim, photo.itemId());
            transactions.savePhotoSuccess(claim, photo.itemId(), new DerivedPhotoKeys(
                    photo.objectKey(), keys.get(0), keys.get(1),
                    null, null, null, null, null, 12L, 8L, 4L, null
            ));
        }
        transactions.completeBatch(claim);
        String outboxId = jdbc.queryForObject(
                "SELECT outbox_id FROM initial_attachment_upload_batches WHERE upload_batch_id = ?",
                String.class, batch.batchId()
        );
        TripCreationOutbox outbox = outboxes.findById(outboxId).orElseThrow();
        transactions.recordPublished(outboxId, new OutboxReceipt(
                outbox.getTopic(), tripId.toString(), outbox.getPayload(), Instant.now()
        ));
        return batch.executionId();
    }

    private Object observeMutation(InvocationOnMock call) throws Throwable {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(lock().isHeldByCurrentThread()).isTrue();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                assertThat(lock().isHeldByCurrentThread()).isTrue();
                commits.incrementAndGet();
            }
        });
        return call.callRealMethod();
    }

    private String status() {
        return jdbc.queryForObject("SELECT processing_status FROM trips WHERE trip_id = ?", String.class, tripId);
    }

    private void assertOutsideMutation() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(lock().isHeldByCurrentThread()).isFalse();
    }

    private RLock lock() {
        return redis.getLock(properties.namespace() + ":lock:trip:mutation:" + tripId);
    }

    private enum Mutation {
        CANCEL, CANCEL_ACK
    }
}

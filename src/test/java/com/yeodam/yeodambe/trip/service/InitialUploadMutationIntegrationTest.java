package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.integration.service.OutboxReceiptService;
import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.entity.TripCreationOutbox;
import com.yeodam.yeodambe.trip.event.TripCreateRequestEvent;
import com.yeodam.yeodambe.trip.event.handler.TripCreateRequestEventHandler;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.repository.TripCreationOutboxRepository;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadCompleteRequest;
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

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {"app.trip.edit-lock.enabled=true", "app.trip.edit-lock.wait-time=100ms"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InitialUploadMutationIntegrationTest {
    @Autowired
    private InitialAttachmentUploadUrlService urls;
    @Autowired
    private InitialAttachmentUploadCompletionService completion;
    @Autowired
    private TripCreateRequestEventHandler requests;
    @Autowired
    private OutboxReceiptService receipts;
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
    private TripAttachmentStorageClient storage;
    @MockitoBean
    private TripAttachmentDerivativeService derivatives;

    private Long tripId;
    private Long ownerId;
    private Long foreignId;
    private final AtomicInteger commits = new AtomicInteger();

    @BeforeEach
    void prepareUpload() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        foreignId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        jdbc.update("UPDATE trips SET processing_status = 'PROCESSING' WHERE trip_id = ?", tripId);
        commits.set(0);
    }

    @Test
    void URL_접수는_커밋까지_락을_유지하고_서명은_락과_트랜잭션_밖에서_수행한다() {
        doAnswer(this::observeMutation).when(transactions).prepareBatch(anyLong(), anyLong(), any());
        doAnswer(call -> {
            assertOutsideMutation();
            return "https://upload.test/photo";
        }).when(storage).createUploadUrl(anyString(), anyString(), anyLong(), any());

        assertThat(urls.issueUploadUrls(tripId, ownerId, request()).attachments()).hasSize(1);

        assertThat(commits).hasValue(1);
        assertOutsideMutation();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM initial_attachment_upload_batches WHERE trip_id = ?",
                Long.class, tripId)).isEqualTo(1L);
    }

    @Test
    void 완료_접수는_S3_검증_후_커밋까지_락을_유지하고_파생_작업은_밖에서_실행한다() {
        InitialAttachmentUploadBatchSnapshot batch = prepareBatch();
        doAnswer(this::observeMutation).when(transactions).startProcessing(anyLong(), anyLong(), anyString());
        doAnswer(call -> {
            assertOutsideMutation();
            return 12L;
        }).when(storage).size(anyString());
        doAnswer(call -> {
            assertOutsideMutation();
            return new ByteArrayInputStream(new byte[]{(byte) 255, (byte) 216, (byte) 255});
        }).when(storage).openHeader(anyString());
        doAnswer(call -> {
            assertOutsideMutation();
            assertThat(commits).hasValue(1);
            return null;
        }).when(derivatives).processAcceptedBatch(anyLong(), anyLong(), anyString());

        completion.complete(tripId, ownerId, new InitialAttachmentUploadCompleteRequest(batch.uploadId()));

        verify(derivatives).processAcceptedBatch(tripId, ownerId, batch.uploadId());
        assertThat(commits).hasValue(1);
        assertOutsideMutation();
    }

    @Test
    void 요청_이벤트의_process_등록은_여행_락을_커밋까지_유지한다() {
        InitialAttachmentUploadBatchSnapshot batch = prepareConvertedBatch();
        jdbc.update("UPDATE initial_attachment_upload_batches SET delivery_status = 'SENT' WHERE upload_batch_id = ?",
                batch.batchId());
        doAnswer(this::observeMutation).when(transactions).enqueueRequest(any());

        requests.handle(new TripCreateRequestEvent(
                "process", tripId, batch.executionId(), null, null, null, null, null, null
        ));

        assertThat(commits).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT process_outbox_id FROM trip_creation_executions WHERE execution_id = ?",
                String.class, batch.executionId())).isNotBlank();
        assertOutsideMutation();
    }

    @Test
    void 발행_확인은_원본_배치와_process_Outbox_변경을_같은_락_커밋으로_보호한다() {
        InitialAttachmentUploadBatchSnapshot batch = prepareConvertedBatch();
        String outboxId = jdbc.queryForObject(
                "SELECT outbox_id FROM initial_attachment_upload_batches WHERE upload_batch_id = ?",
                String.class, batch.batchId());
        TripCreationOutbox outbox = outboxes.findById(outboxId).orElseThrow();
        doAnswer(this::observeMutation).when(transactions).recordPublished(anyString(), any());

        receipts.observe("trip_creation_outbox", outboxId, new OutboxReceipt(
                outbox.getTopic(), tripId.toString(), outbox.getPayload(), Instant.now()
        ));

        assertThat(commits).hasValue(1);
        assertThat(outboxes.findById(outboxId).orElseThrow().getPublishedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT process_outbox_id FROM trip_creation_executions WHERE execution_id = ?",
                String.class, batch.executionId())).isNotBlank();
        assertOutsideMutation();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void 타인_접수는_경합_중에도_기존_여행_접근_거절을_유지한다(Operation operation) throws Exception {
        assertRejectedWhileLocked(operation, foreignId, TripNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void 소유자_접수는_경합하면_배치를_변경하거나_파생_작업을_실행하지_않는다(Operation operation) throws Exception {
        assertRejectedWhileLocked(operation, ownerId, TripEditLockRejectedException.class);
    }

    private void assertRejectedWhileLocked(
            Operation operation, Long actorId, Class<? extends RuntimeException> expected
    ) throws Exception {
        InitialAttachmentUploadBatchSnapshot batch = operation == Operation.COMPLETE ? prepareBatch() : null;
        if (operation == Operation.COMPLETE && actorId.equals(ownerId)) {
            doAnswer(call -> {
                assertOutsideMutation();
                return 12L;
            }).when(storage).size(anyString());
            doAnswer(call -> {
                assertOutsideMutation();
                return new ByteArrayInputStream(new byte[]{(byte) 255, (byte) 216, (byte) 255});
            }).when(storage).openHeader(anyString());
        }
        RLock lock = lock();
        lock.lock();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> response = executor.submit(() -> {
                if (operation == Operation.URL) {
                    urls.issueUploadUrls(tripId, actorId, request());
                } else {
                    completion.complete(tripId, actorId, new InitialAttachmentUploadCompleteRequest(batch.uploadId()));
                }
            });
            assertThatThrownBy(() -> response.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(expected);
        } finally {
            lock.unlock();
        }
        verifyNoInteractions(derivatives);
        if (operation == Operation.URL || actorId.equals(foreignId)) {
            verifyNoInteractions(storage);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM initial_attachment_upload_batches WHERE trip_id = ? AND accepted_at IS NOT NULL",
                Long.class, tripId)).isZero();
    }

    private InitialAttachmentUploadBatchSnapshot prepareBatch() {
        return transactions.prepareBatch(tripId, ownerId, request());
    }

    private InitialAttachmentUploadBatchSnapshot prepareConvertedBatch() {
        InitialAttachmentUploadBatchSnapshot batch = prepareBatch();
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
        return batch;
    }

    private InitialAttachmentUploadUrlRequest request() {
        return new InitialAttachmentUploadUrlRequest(1, 1, true, List.of(
                new InitialAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 12L)
        ));
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

    private void assertOutsideMutation() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(lock().isHeldByCurrentThread()).isFalse();
    }

    private RLock lock() {
        return redis.getLock(properties.namespace() + ":lock:trip:mutation:" + tripId);
    }

    private enum Operation {
        URL, COMPLETE
    }
}

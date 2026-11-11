package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.AdditionalAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.trip.service.response.PreparedPhotoDerivative;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DbConversionMutationBoundaryIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private InitialAttachmentStoragePipeline pipeline;
    @Autowired
    private AdditionalAttachmentDerivativeWorker worker;
    @Autowired
    private AdditionalAttachmentUploadUrlService admission;
    @Autowired
    private AdditionalAttachmentAdmissionTransactionService v3Admission;
    @Autowired
    private TripMutationCoordinator mutations;
    @Autowired
    private TripEditLockProperties properties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;
    @MockitoSpyBean
    private InitialAttachmentUploadTransactionService initial;
    @MockitoSpyBean
    private AdditionalAttachmentUploadTransactionService additional;
    @MockitoBean
    private TripAttachmentDerivativeService derivatives;
    @MockitoBean
    private TripAttachmentStorageClient storage;
    @TempDir
    private Path directory;
    private Long ownerId;
    private Long tripId;
    private final AtomicBoolean savedWithLock = new AtomicBoolean();
    private final AtomicBoolean committedWithLock = new AtomicBoolean();
    private final AtomicBoolean externalOutsideBoundary = new AtomicBoolean(true);
    private final AtomicInteger externalCalls = new AtomicInteger();

    @BeforeEach
    void prepareTrip() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        when(storage.createUploadUrl(anyString(), anyString(), anyLong(), any()))
                .thenReturn("https://storage.test/upload");
        when(storage.storeDerivedToAsync(anyString(), any(), anyString())).thenAnswer(invocation -> {
            observeExternal();
            return CompletableFuture.completedFuture(invocation.getArgument(0, String.class));
        });
        doAnswer(invocation -> {
            observeExternal();
            return null;
        }).when(storage).retain(anyList());
        doAnswer(invocation -> {
            observeMutation();
            return invocation.callRealMethod();
        }).when(initial).savePhotoSuccess(any(InitialAttachmentUploadBatchSnapshot.class), anyLong(), any());
        doAnswer(invocation -> {
            observeMutation();
            return invocation.callRealMethod();
        }).when(additional).savePhotoSuccess(any(AdditionalAttachmentUploadBatchSnapshot.class), anyLong(), any());
    }

    @Test
    void 최초_파이프라인은_외부_저장_뒤_여행_락을_유지한_상태에서_사진을_커밋한다() {
        jdbc.update("UPDATE trips SET processing_status = 'PROCESSING' WHERE trip_id = ?", tripId);
        InitialAttachmentUploadBatchSnapshot prepared = initial.prepareBatch(
                tripId,
                ownerId,
                new InitialAttachmentUploadUrlRequest(1, 1, true, List.of(
                        new InitialAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 12L)))
        );
        initial.startProcessing(tripId, ownerId, prepared.uploadId());
        InitialAttachmentUploadBatchSnapshot claim = initial.claimBatch(tripId, ownerId, prepared.uploadId())
                .orElseThrow();

        pipeline.start(claim, this::preparePhoto, failure -> false);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM initial_attachment_upload_batches WHERE upload_id = ?",
                String.class, prepared.uploadId())).isEqualTo("READY"));
        assertBoundaryAndAccounting(12L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 추가_워커는_외부_변환_뒤_여행_락을_유지한_상태에서_사진을_커밋한다(boolean v3) {
        AdditionalAttachmentUploadUrlRequest request = new AdditionalAttachmentUploadUrlRequest(
                UUID.randomUUID().toString(), 1, 1, true, List.of(
                        new AdditionalAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 12L))
        );
        String uploadId;
        if (v3) {
            uploadId = mutations.execute(tripId,
                    () -> v3Admission.prepareBatch(tripId, ownerId, request)).getUploadId();
        } else {
            uploadId = admission.issueUploadUrls(tripId, ownerId, request).uploadId();
            new TripAnalysisTestFixture(jdbc).markLegacyUpload(uploadId);
        }
        additional.markVerified(tripId, ownerId, uploadId);
        when(derivatives.generateAdditional(anyString(), anyString(), anyString(), anyList()))
                .thenAnswer(invocation -> {
                    observeExternal();
                    List<String> keys = invocation.getArgument(3);
                    return output(invocation.getArgument(1), keys);
                });

        worker.processPendingBatches();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM additional_attachment_upload_batches WHERE upload_id = ?",
                String.class, uploadId)).isEqualTo("PREPARED"));
        assertBoundaryAndAccounting(0L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM additional_attachment_upload_outbox WHERE trip_id = ?",
                Long.class, tripId)).isEqualTo(v3 ? 0L : 1L);
    }

    private void observeExternal() {
        externalCalls.incrementAndGet();
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || redis.getLock(lockKey()).isHeldByCurrentThread()) {
            externalOutsideBoundary.set(false);
        }
    }

    private void observeMutation() {
        savedWithLock.set(TransactionSynchronizationManager.isActualTransactionActive()
                && redis.getLock(lockKey()).isHeldByCurrentThread());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    committedWithLock.set(redis.getLock(lockKey()).isHeldByCurrentThread());
                }
            });
        }
    }

    private void assertBoundaryAndAccounting(long reservedBytes) {
        assertThat(externalCalls.get()).isPositive();
        assertThat(externalOutsideBoundary).isTrue();
        assertThat(savedWithLock).isTrue();
        assertThat(committedWithLock).isTrue();
        assertThat(redis.getLock(lockKey()).isLocked()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_attachments WHERE trip_id = ?",
                Long.class, tripId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?",
                Long.class, ownerId)).isEqualTo(reservedBytes);
    }

    private String lockKey() {
        return properties.namespace() + ":lock:trip:mutation:" + tripId;
    }

    private PreparedPhotoDerivative preparePhoto(
            InitialAttachmentUploadBatchSnapshot.Photo photo,
            List<String> keys
    ) {
        observeExternal();
        try {
            Path work = Files.createTempDirectory(directory, "photo-");
            Path analyze = Files.write(work.resolve("analyze.jpg"), new byte[8]);
            Path preview = Files.write(work.resolve("preview.webp"), new byte[4]);
            return new PreparedPhotoDerivative(work, output(photo.objectKey(), keys), List.of(
                    new PreparedPhotoDerivative.Output(analyze, keys.get(0), "image/jpeg"),
                    new PreparedPhotoDerivative.Output(preview, keys.get(1), "image/webp")));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static DerivedPhotoKeys output(String originalKey, List<String> keys) {
        return new DerivedPhotoKeys(
                originalKey,
                keys.get(0),
                keys.get(1),
                null,
                null,
                null,
                null,
                null,
                12L,
                8L,
                4L,
                null
        );
    }
}

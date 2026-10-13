package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import com.yeodam.yeodambe.user.service.UserStatsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.application.name=initial-upload-transaction-test")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InitialAttachmentUploadTransactionServiceTest {
    @Autowired
    private InitialAttachmentUploadTransactionService transactions;
    @Autowired
    private TripRepository trips;
    @Autowired
    private UserRepository users;
    @Autowired
    private UserStatsRepository stats;
    @Autowired
    private InitialAttachmentUploadItemRepository items;
    @Autowired
    private TripCreationExecutionRepository executions;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private StoredFileRepository files;
    @Autowired
    private TripAttachmentRepository attachments;
    @MockitoSpyBean
    private UserStatsService userStatsService;
    private User owner;
    private Trip trip;

    @BeforeEach
    void prepareUser() {
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "접수회원"));
        stats.save(new UserStats(owner));
        trip = trips.save(new Trip(owner.getUserId(), "접수 여행", LocalDate.now(), LocalDate.now()));
    }

    @AfterEach
    void finishTestExecution() {
        executions.findByTripId(trip.getId()).ifPresent(execution ->
                transactions.failExecution(execution.getExecutionId(), "TEST_FINISHED"));
    }

    @Test
    void 재발급은_같은_키와_예약을_재사용한다() {
        var first = prepare(false);
        var repeated = prepare(false);
        assertThat(repeated.uploadId()).isEqualTo(first.uploadId());
        assertThat(repeated.photos()).isEqualTo(first.photos());
        assertThat(reserved()).isEqualTo(12L);
        transactions.startProcessing(trip.getId(), owner.getUserId(), first.uploadId());
        var next = transactions.prepareBatch(trip.getId(), owner.getUserId(), request(2, 2, true));
        assertThat(next.batchNo()).isEqualTo(2);
        assertThat(reserved()).isEqualTo(24L);
    }

    @Test
    void 접수_중복은_작업과_예약을_늘리지_않는다() {
        var batch = prepare(true);
        transactions.startProcessing(trip.getId(), owner.getUserId(), batch.uploadId());
        transactions.startProcessing(trip.getId(), owner.getUserId(), batch.uploadId());
        assertThat(reserved()).isEqualTo(12L);
        assertThat(execution(batch).isInputClosed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM files WHERE user_id = ?", Integer.class, owner.getUserId())).isZero();
    }

    @Test
    void 이전_선점_token으로_사진_성공을_저장할_수_없다() {
        var batch = prepare(true);
        transactions.startProcessing(trip.getId(), owner.getUserId(), batch.uploadId());
        var old = transactions.claimBatch().orElseThrow();
        jdbc.update("UPDATE initial_attachment_upload_batches SET lease_until = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE upload_batch_id = ?", batch.batchId());
        var current = transactions.claimBatch().orElseThrow();
        assertThat(current.claimToken()).isNotEqualTo(old.claimToken());
        assertThatThrownBy(() -> transactions.savePhotoSuccess(old, old.photos().getFirst().itemId(), derived(old.photos().getFirst().objectKey())))
                .isInstanceOf(CancellationException.class);
        transactions.releaseSaturatedBatch(current);
    }

    @Test
    void 포화로_배치를_재예약해도_사진_시도는_소비하지_않는다() {
        var batch = prepare(true);
        transactions.startProcessing(trip.getId(), owner.getUserId(), batch.uploadId());
        var claim = transactions.claimBatch().orElseThrow();
        transactions.releaseSaturatedBatch(claim);
        assertThat(items.findById(claim.photos().getFirst().itemId()).orElseThrow().getConversionAttempts()).isZero();
        transactions.failExecution(batch.executionId(), "TEST_FINISH");
    }

    @Test
    void 실패시_예약은_한번만_반환하고_정리시각을_영속_보존한다() {
        var batch = prepare(true);
        transactions.startProcessing(trip.getId(), owner.getUserId(), batch.uploadId());
        transactions.failExecution(batch.executionId(), "TEST_FAILURE");
        var ended = execution(batch).getEndedAt();
        transactions.failExecution(batch.executionId(), "AGAIN");
        assertThat(execution(batch).getEndedAt()).isEqualTo(ended);
        assertThat(reserved()).isZero();
        assertThat(items.findById(batch.photos().getFirst().itemId()).orElseThrow().getCleanupStartedAt()).isEqualTo(ended);
    }

    @Test
    void 일시_변환_실패는_사진별_세번까지만_시도한다() {
        var batch = prepare(true);
        transactions.startProcessing(trip.getId(), owner.getUserId(), batch.uploadId());
        for (int attempt = 1; attempt <= 3; attempt++) {
            var claim = transactions.claimBatch().orElseThrow();
            Long itemId = claim.photos().getFirst().itemId();
            transactions.startPhotoAttempt(claim, itemId);
            transactions.retryBatch(claim, itemId, false);
            assertThat(items.findById(itemId).orElseThrow().getConversionAttempts()).isEqualTo(attempt);
            jdbc.update("UPDATE initial_attachment_upload_batches SET next_attempt_at = NULL WHERE upload_batch_id = ?", batch.batchId());
            jdbc.update("UPDATE initial_attachment_upload_items SET conversion_next_attempt_at = NULL WHERE upload_item_id = ?", itemId);
        }
        assertThat(execution(batch).getStatus()).isEqualTo(TripCreationExecution.Status.FAILED);
        assertThat(reserved()).isZero();
        assertThat(transactions.claimBatch()).isEmpty();
    }

    @Test
    void 전체_접수_전만_십분_무활동으로_만료한다() {
        var batch = prepare(false);
        jdbc.update("UPDATE trip_creation_executions SET last_upload_activity_at = CURRENT_TIMESTAMP(6) - INTERVAL 599 SECOND WHERE execution_id = ?", batch.executionId());
        transactions.failExpiredExecution(batch.executionId(), false);
        assertThat(execution(batch).isTerminal()).isFalse();
        jdbc.update("UPDATE trip_creation_executions SET last_upload_activity_at = CURRENT_TIMESTAMP(6) - INTERVAL 10 MINUTE WHERE execution_id = ?", batch.executionId());
        transactions.failExpiredExecution(batch.executionId(), false);
        assertThat(execution(batch).getErrorCode()).isEqualTo("UPLOAD_TIMEOUT");
        assertThat(reserved()).isZero();
    }

    @Test
    void 전체_접수_후에는_업로드_만료를_적용하지_않는다() {
        var batch = prepare(true);
        transactions.startProcessing(trip.getId(), owner.getUserId(), batch.uploadId());
        jdbc.update("UPDATE trip_creation_executions SET last_upload_activity_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 HOUR WHERE execution_id = ?", batch.executionId());
        transactions.failExpiredExecution(batch.executionId(), false);
        assertThat(execution(batch).isTerminal()).isFalse();
        assertThat(reserved()).isEqualTo(12L);
    }

    @Test
    void 동시_동일_선언은_하나의_실행과_예약을_공유한다() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> prepare(true));
            var second = executor.submit(() -> prepare(true));
            assertThat(first.get(10, TimeUnit.SECONDS).uploadId())
                    .isEqualTo(second.get(10, TimeUnit.SECONDS).uploadId());
        }
        assertThat(reserved()).isEqualTo(12L);
    }

    @Test
    void 성공_기록_후_오류는_파생본을_정리하지_않고_재사용한다() {
        var batch = prepare(true);
        transactions.startProcessing(trip.getId(), owner.getUserId(), batch.uploadId());
        var claim = transactions.claimBatch().orElseThrow();
        var photo = claim.photos().getFirst();
        var keys = transactions.startPhotoAttempt(claim, photo.itemId());
        transactions.savePhotoSuccess(claim, photo.itemId(), new DerivedPhotoKeys(
                photo.objectKey(), keys.get(0), keys.get(1), null, null, null, null, null, 12L, 8L, 4L, null));
        transactions.retryBatch(claim, photo.itemId(), true);
        var item = items.findById(photo.itemId()).orElseThrow();
        assertThat(item.getDerivedJson()).isNotNull();
        assertThat(item.getTripAttachmentId()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM files WHERE user_id = ?", Integer.class, owner.getUserId()))
                .isEqualTo(1);
        assertThat(transactions.cleanupObjects(item)).allMatch(object -> object.startedAt() == null);
        assertThat(execution(batch).isTerminal()).isFalse();
    }

    @Test
    void 먼저_읽은_접수_후보가_있어도_잠금_뒤_접수_상태를_갱신한다() {
        var batch = prepare(true);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            transactions.loadAcceptanceCandidate(trip.getId(), owner.getUserId(), batch.uploadId());
            jdbc.update("UPDATE initial_attachment_upload_batches SET accepted_at = CURRENT_TIMESTAMP(6), status = 'ACCEPTED' WHERE upload_batch_id = ?", batch.batchId());
            jdbc.update("UPDATE trip_creation_executions SET input_closed = TRUE, status = 'IMAGE_PROCESSING' WHERE execution_id = ?", batch.executionId());
            assertThat(transactions.startProcessing(trip.getId(), owner.getUserId(), batch.uploadId()).status())
                    .isEqualTo(TripProcessingStatusResponse.Status.PROCESSING);
        });
    }

    @Test
    void 먼저_읽은_사용자_통계가_있어도_최신_다른_예약을_보존한다() {
        var batch = prepare(true);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(stats.findByUser_UserId(owner.getUserId()).orElseThrow().getStorageReservedBytes()).isEqualTo(12L);
            jdbc.update("UPDATE user_stats SET storage_reserved_bytes = 24 WHERE user_id = ?", owner.getUserId());
            transactions.failExecution(batch.executionId(), "TEST_FAILED");
        });
        assertThat(reserved()).isEqualTo(12L);
    }

    @Test
    void 다른_여행_완료를_기다린_예약도_최신_원본_사용량으로_한도를_검사한다() throws Exception {
        Trip completed = trips.save(new Trip(owner.getUserId(), "다른 여행", LocalDate.now(), LocalDate.now()));
        var file = StoredFile.uploaded(owner.getUserId(), "photo.jpg", "quota-" + UUID.randomUUID(), "image/jpeg");
        file.storageSize(12L);
        file = files.save(file);
        attachments.save(TripAttachment.initial(completed.getId(), file.getId(), "analyze", "preview"));
        jdbc.update("UPDATE user_stats SET storage_maximum_bytes = 13 WHERE user_id = ?", owner.getUserId());
        var locked = new CountDownLatch(1);
        var reserving = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            reserving.countDown();
            return invocation.callRealMethod();
        }).when(userStatsService).reserveInitialUpload(owner.getUserId(), 12L);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var completion = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ? FOR UPDATE", Long.class, owner.getUserId());
                    locked.countDown();
                    try {
                        if (!reserving.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("예약 시작 대기 실패");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                    jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", completed.getId());
                    jdbc.update("UPDATE user_stats SET storage_used_bytes = 12 WHERE user_id = ?", owner.getUserId());
                });
            });
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var reservation = executor.submit(() -> prepare(true));
            completion.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> reservation.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AttachmentUploadLimitExceededException.class);
        }
        assertThat(reserved()).isZero();
        assertThat(jdbc.queryForObject("SELECT storage_used_bytes FROM user_stats WHERE user_id = ?", Long.class, owner.getUserId()))
                .isEqualTo(12L);
    }

    private InitialAttachmentUploadBatchSnapshot prepare(boolean last) {
        return transactions.prepareBatch(trip.getId(), owner.getUserId(), request(1, last ? 1 : 2, last));
    }

    private InitialAttachmentUploadUrlRequest request(
            int number,
            int count,
            boolean last
    ) {
        return new InitialAttachmentUploadUrlRequest(number, count, last,
                List.of(new InitialAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 12L)));
    }

    private TripCreationExecution execution(InitialAttachmentUploadBatchSnapshot batch) {
        return executions.findById(batch.executionId()).orElseThrow();
    }

    private long reserved() {
        return jdbc.queryForObject("SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?", Long.class, owner.getUserId());
    }

    private DerivedPhotoKeys derived(String key) {
        return new DerivedPhotoKeys(key, key + "-analyze", key + "-preview", null,
                null, null, null, null, 12L, 8L, 4L, null);
    }
}

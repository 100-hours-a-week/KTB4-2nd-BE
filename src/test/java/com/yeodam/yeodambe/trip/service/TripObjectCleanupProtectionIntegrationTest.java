package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture.Addition;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripObjectCleanupProtectionIntegrationTest {
    @Autowired
    private TripAnalysisExecutionService scheduling;
    @Autowired
    private TripAnalysisResultApplicationService results;
    @Autowired
    private AdditionalAttachmentUploadTransactionService uploads;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TripObjectCleanupService cleanup;
    @Autowired
    private TripUploadTimeoutService timeouts;
    @Autowired
    private TripEditLockProperties lockProperties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;
    @MockitoSpyBean
    private InitialAttachmentUploadTransactionService initialUploads;
    @MockitoBean
    private TripAttachmentStorageClient storage;
    private TripAnalysisTestFixture fixture;
    private Long ownerId;
    private Long tripId;

    @BeforeEach
    void prepareTrip() {
        fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        fixture.createPhoto(tripId, ownerId);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(storage).delete(anyString());
    }

    @AfterEach
    void isolateClaims() {
        jdbc.update("UPDATE trip_analysis_executions SET result_next_attempt_at = '2099-01-01' WHERE trip_id = ?", tripId);
        jdbc.update("UPDATE trips SET processing_status = 'CANCELED' WHERE user_id = ? AND processing_status = 'PROCESSING'", ownerId);
    }

    @Test
    void 미시작_여행의_만료도_여행_락을_유지한_상태에서_커밋한다() {
        jdbc.update("""
                INSERT INTO trips (user_id, trip_name, start_date, end_date, processing_status, created_at)
                VALUES (?, '만료검증', '2026-09-01', '2026-09-02', 'PROCESSING', CURRENT_TIMESTAMP(6) - INTERVAL 11 MINUTE)
                """, ownerId);
        Long expiredTrip = jdbc.queryForObject(
                "SELECT trip_id FROM trips WHERE user_id = ? AND processing_status = 'PROCESSING'", Long.class, ownerId);
        AtomicBoolean committedWithLock = new AtomicBoolean();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(redis.getLock(lockKey(expiredTrip)).isHeldByCurrentThread()).isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    assertThat(redis.getLock(lockKey(expiredTrip)).isHeldByCurrentThread()).isTrue();
                    committedWithLock.set(true);
                }
            });
            return invocation.callRealMethod();
        }).when(initialUploads).failUnstartedTrip(expiredTrip);

        timeouts.failExpiredUploads();

        assertThat(committedWithLock).isTrue();
        assertThat(redis.getLock(lockKey(expiredTrip)).isHeldByCurrentThread()).isFalse();
        assertThat(jdbc.queryForObject("SELECT processing_status FROM trips WHERE trip_id = ?", String.class, expiredTrip))
                .isEqualTo("FAILED");
    }

    private String lockKey(Long id) {
        return lockProperties.namespace() + ":lock:trip:mutation:" + id;
    }

    @Test
    void 저장_보류_입력의_사본은_별도_정리_의도가_오래되어도_삭제하지_않는다() {
        Addition addition = fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        String analyzeKey = jdbc.queryForObject("SELECT analyze_storage_key FROM trip_attachments WHERE trip_attachment_id = ?",
                String.class, addition.photoId());
        jdbc.update("UPDATE trip_analysis_executions SET status = 'APPLY_BLOCKED' WHERE execution_id = ?", executionId);
        LocalDateTime old = uploads.databaseTime().minusDays(31);
        List<AdditionalAttachmentUploadTransactionService.CleanupObject> journal = List.of(
                new AdditionalAttachmentUploadTransactionService.CleanupObject(analyzeKey, old, "COMPENSATION", false));
        jdbc.update("UPDATE additional_attachment_upload_items SET cleanup_json = ?, cleanup_started_at = ? WHERE upload_batch_id = ?",
                json.writeValueAsString(journal), old, addition.batchId());
        jdbc.update("UPDATE trip_attachments SET deleted_at = ? WHERE trip_attachment_id = ?", old, addition.photoId());
        cleanup.retryPending();
        verify(storage, never()).delete(analyzeKey);
        assertThat(jdbc.queryForObject("SELECT ended_at IS NULL FROM trip_analysis_executions WHERE execution_id = ?",
                Boolean.class, executionId)).isTrue();
    }

    @Test
    void BE_타임아웃_뒤에도_AI_종료가_미확인된_사본은_보존한다() {
        Addition addition = fixture.prepareAddition(tripId, ownerId);
        jdbc.update("UPDATE additional_attachment_executions SET analysis_failure_count = 1 WHERE execution_id = ?",
                addition.additionId());
        String executionId = scheduling.startNext(tripId).orElseThrow();
        jdbc.update("UPDATE trip_analysis_executions SET process_published_at = '2000-01-01' WHERE execution_id = ?", executionId);
        results.expire();
        String analyzeKey = jdbc.queryForObject("SELECT analyze_storage_key FROM trip_attachments WHERE trip_attachment_id = ?",
                String.class, addition.photoId());
        LocalDateTime old = uploads.databaseTime().minusDays(31);
        List<AdditionalAttachmentUploadTransactionService.CleanupObject> journal = List.of(
                new AdditionalAttachmentUploadTransactionService.CleanupObject(analyzeKey, old, "AI_RESULT_TIMEOUT", false));
        jdbc.update("UPDATE additional_attachment_upload_items SET cleanup_json = ?, cleanup_started_at = ? WHERE upload_batch_id = ?",
                json.writeValueAsString(journal), old, addition.batchId());
        jdbc.update("UPDATE trip_attachments SET deleted_at = ? WHERE trip_attachment_id = ?", old, addition.photoId());
        cleanup.retryPending();
        verify(storage, never()).delete(analyzeKey);
        TripCreateResultEvent terminal = new TripCreateResultEvent("process_result", tripId, executionId,
                TripCreateResultEvent.Status.CANCELED, null, null);
        results.receive(terminal, json.writeValueAsString(terminal));
        cleanup.retryPending();
        verify(storage, atLeastOnce()).delete(analyzeKey);
    }

    @Test
    void 기존_실행도_원격_종료가_미확인되면_입력_사본을_보존한다() {
        Addition addition = fixture.prepareAddition(tripId, ownerId);
        String analyzeKey = jdbc.queryForObject("SELECT analyze_storage_key FROM trip_attachments WHERE trip_attachment_id = ?",
                String.class, addition.photoId());
        String input = json.writeValueAsString(Map.of("type", "process", "trip_id", tripId,
                "execution_id", addition.additionId(), "attachments",
                List.of(Map.of("trip_attachment_id", addition.photoId(), "analyze_storage_key", analyzeKey))));
        jdbc.update("""
                INSERT INTO additional_attachment_upload_outbox (upload_batch_id, message_type, payload, request_id)
                VALUES (?, 'process', ?, ?)
                """, addition.batchId(), input, addition.additionId());
        Long outboxId = jdbc.queryForObject("SELECT outbox_id FROM additional_attachment_upload_outbox WHERE upload_batch_id = ?",
                Long.class, addition.batchId());
        LocalDateTime old = uploads.databaseTime().minusDays(31);
        jdbc.update("""
                UPDATE additional_attachment_executions SET processing_mode = 'LEGACY', status = 'FAILED',
                    error_code = 'AI_RESULT_TIMEOUT', ended_at = ?, process_published_at = ?, process_outbox_id = ?
                WHERE execution_id = ?
                """, old, old, outboxId, addition.additionId());
        jdbc.update("UPDATE trip_attachments SET deleted_at = ? WHERE trip_attachment_id = ?", old, addition.photoId());
        cleanup.retryPending();
        verify(storage, never()).delete(analyzeKey);
        jdbc.update("UPDATE additional_attachment_executions SET remote_terminal_confirmed_at = CURRENT_TIMESTAMP(6) WHERE execution_id = ?",
                addition.additionId());
        cleanup.retryPending();
        verify(storage, atLeastOnce()).delete(analyzeKey);
    }
}

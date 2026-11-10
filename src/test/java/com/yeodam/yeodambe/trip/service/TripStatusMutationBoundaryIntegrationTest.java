package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.integration.service.response.TripPhotoAnalysisStatusResponse;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture.Addition;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripStatusMutationBoundaryIntegrationTest {
    @Autowired
    private TripProcessingStatusService status;
    @Autowired
    private TripAnalysisExecutionService scheduling;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TripEditLockProperties properties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;
    @MockitoSpyBean
    private TripProcessingStatusSyncService sync;
    @MockitoBean
    private TripPhotoAnalysisService remote;
    @MockitoBean
    private TripEditNotificationPublisher publisher;
    private TripAnalysisTestFixture fixture;
    private Long ownerId;
    private Long tripId;
    private final AtomicBoolean committedWithLock = new AtomicBoolean();

    @BeforeEach
    void prepareTrip() {
        fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 생성과_추가_상태는_AI를_경계_밖에서_조회하고_락을_유지한_채_커밋한다(boolean creation) {
        String executionId = preparePublishedExecution(creation);
        when(remote.findStatus(tripId, executionId)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(lock().isHeldByCurrentThread()).isFalse();
            return new TripPhotoAnalysisStatusResponse(
                    tripId, TripPhotoAnalysisStatusResponse.Status.PROCESSING,
                    new TripPhotoAnalysisStatusResponse.Progress(0, 1), "CLUSTERING", null, null
            );
        });
        doAnswer(invocation -> {
            observeMutation();
            return invocation.callRealMethod();
        }).when(sync).synchronizeCreation(anyString(), anyLong(), any(), any());
        doAnswer(invocation -> {
            observeMutation();
            return invocation.callRealMethod();
        }).when(sync).synchronizeAddition(anyString(), anyLong(), any(), any());

        status.synchronizeStatus(tripId, ownerId);

        assertThat(status.findStatus(tripId, ownerId).currentStep()).isEqualTo("CLUSTERING");
        assertThat(committedWithLock).isTrue();
        assertThat(lock().isLocked()).isFalse();
    }

    @Test
    void 나중_묶음이_종료돼도_남아_있는_업로드를_우선_조회한다() {
        Addition pending = fixture.prepareAddition(tripId, ownerId);
        jdbc.update("UPDATE additional_attachment_executions SET input_closed = FALSE WHERE execution_id = ?",
                pending.additionId());
        Addition later = fixture.prepareAddition(tripId, ownerId);
        fixture.completeAnalysis(scheduling.startNext(tripId).orElseThrow());
        jdbc.update("UPDATE additional_attachment_executions SET display_status = 'COMPLETED' WHERE execution_id = ?",
                later.additionId());

        TripProcessingStatusResponse response = status.findStatus(tripId, ownerId);

        assertThat(response.executionId()).isEqualTo(pending.additionId());
        assertThat(response.status()).isEqualTo(TripProcessingStatusResponse.Status.PROCESSING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NORMAL", "VERSION_CHANGED", "RESULT_RECEIVED", "TERMINAL"})
    void 분석_상태는_확정된_묶음과_현재_버전에만_반영하고_늦은_조회가_결과를_덮지_않는다(String race) {
        Addition first = fixture.prepareAddition(tripId, ownerId);
        Addition second = fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        Addition later = fixture.prepareAddition(tripId, ownerId);
        jdbc.update("UPDATE trip_analysis_executions SET process_published_at = CURRENT_TIMESTAMP(6) WHERE execution_id = ?",
                executionId);
        long previousNotifications = notificationCount();
        when(remote.findStatus(tripId, executionId)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(lock().isHeldByCurrentThread()).isFalse();
            if (race.equals("VERSION_CHANGED")) {
                jdbc.update("""
                        UPDATE additional_attachment_executions
                        SET current_step = 'NEWER', status_version = status_version + 1 WHERE execution_id = ?
                        """, first.additionId());
            } else if (race.equals("RESULT_RECEIVED")) {
                jdbc.update("""
                        UPDATE trip_analysis_executions SET status = 'RESULT_APPLYING', result_raw_payload = '{}'
                        WHERE execution_id = ?
                        """, executionId);
            } else if (race.equals("TERMINAL")) {
                jdbc.update("""
                        UPDATE trip_analysis_executions SET status = 'COMPLETED', ended_at = CURRENT_TIMESTAMP(6)
                        WHERE execution_id = ?
                        """, executionId);
            }
            return new TripPhotoAnalysisStatusResponse(
                    tripId, TripPhotoAnalysisStatusResponse.Status.PROCESSING,
                    new TripPhotoAnalysisStatusResponse.Progress(1, 2), "CLUSTERING", null, null
            );
        });
        doAnswer(invocation -> {
            observeMutation();
            return invocation.callRealMethod();
        }).when(sync).synchronizeAnalysis(anyString(), any(), any(), any());

        status.synchronizeAnalysis(executionId);

        if (race.equals("RESULT_RECEIVED") || race.equals("TERMINAL")) {
            assertThat(step(first.additionId())).isNull();
            assertThat(step(second.additionId())).isNull();
            assertThat(notificationCount()).isEqualTo(previousNotifications);
        } else {
            assertThat(step(first.additionId())).isEqualTo(race.equals("VERSION_CHANGED") ? "NEWER" : "CLUSTERING");
            assertThat(step(second.additionId())).isEqualTo("CLUSTERING");
        }
        assertThat(step(later.additionId())).isNull();
        assertThat(committedWithLock).isTrue();
        assertThat(lock().isLocked()).isFalse();
    }

    @Test
    void AI가_확정_입력과_다른_사진_수를_반환하면_상태와_알림을_변경하지_않는다() {
        Addition first = fixture.prepareAddition(tripId, ownerId);
        Addition second = fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        jdbc.update("UPDATE trip_analysis_executions SET process_published_at = CURRENT_TIMESTAMP(6) WHERE execution_id = ?",
                executionId);
        long previousNotifications = notificationCount();
        when(remote.findStatus(tripId, executionId)).thenReturn(new TripPhotoAnalysisStatusResponse(
                tripId, TripPhotoAnalysisStatusResponse.Status.PROCESSING,
                new TripPhotoAnalysisStatusResponse.Progress(0, 1), "CLUSTERING", null, null
        ));

        assertThatThrownBy(() -> status.synchronizeAnalysis(executionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TripInternalErrorMessage.AI_PROGRESS_TOTAL_EXECUTION_MISMATCH.message());

        assertThat(step(first.additionId())).isNull();
        assertThat(step(second.additionId())).isNull();
        assertThat(notificationCount()).isEqualTo(previousNotifications);
        assertThat(lock().isLocked()).isFalse();
    }

    private String step(String additionId) {
        return jdbc.queryForObject("SELECT current_step FROM additional_attachment_executions WHERE execution_id = ?",
                String.class, additionId);
    }

    private long notificationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_notification_outbox WHERE target_type = 'TRIP' AND target_id = ?",
                Long.class, tripId);
    }

    private String preparePublishedExecution(boolean creation) {
        if (creation) {
            String executionId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO trip_creation_executions
                        (execution_id, trip_id, user_id, status, total_attachment_count, process_published_at)
                    VALUES (?, ?, ?, 'AI_QUEUED', 1, CURRENT_TIMESTAMP(6))
                    """, executionId, tripId, ownerId);
            return executionId;
        }
        Addition addition = fixture.prepareAddition(tripId, ownerId);
        jdbc.update("""
                UPDATE additional_attachment_executions
                SET processing_mode = 'LEGACY', status = 'AI_QUEUED', analysis_attachment_count = 1,
                    process_published_at = CURRENT_TIMESTAMP(6)
                WHERE execution_id = ?
                """, addition.additionId());
        return addition.additionId();
    }

    private void observeMutation() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(lock().isHeldByCurrentThread()).isTrue();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                committedWithLock.set(lock().isHeldByCurrentThread());
            }
        });
    }

    private RLock lock() {
        return redis.getLock(properties.namespace() + ":lock:trip:mutation:" + tripId);
    }
}

package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.integration.service.response.TripPhotoAnalysisStatusResponse;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture.Addition;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.response.AdditionalAttachmentProcessingStatusResponse;
import com.yeodam.yeodambe.trip.service.response.TripAnalysisResultClaim;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripWorkflowStatusIntegrationTest {
    @Autowired
    private TripProcessingStatusService status;
    @Autowired
    private TripAnalysisExecutionService scheduling;
    @Autowired
    private TripAnalysisResultApplicationService results;
    @Autowired
    private TripDeletionService deletion;
    @Autowired
    private TripShareUserRepository shares;
    @MockitoBean
    private TripPhotoAnalysisService remote;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private TripAnalysisTestFixture fixture;
    private Long ownerId;
    private Long tripId;
    private Addition addition;

    @BeforeEach
    void prepareTrip() {
        fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        addition = fixture.prepareAddition(tripId, ownerId);
    }

    @AfterEach
    void isolatePendingClaims() {
        jdbc.update("UPDATE trip_analysis_executions SET result_next_attempt_at = '2099-01-01' WHERE trip_id = ?", tripId);
    }

    @Test
    void 저장_보류는_종료가_아닌_FINALIZING과_별도_APPLY_BLOCKED로_조회한다() {
        String executionId = scheduling.startNext(tripId).orElseThrow();
        receiveSuccess(executionId);
        for (int attempt = 0; attempt < 3; attempt++) {
            TripAnalysisResultClaim claim = results.claim().orElseThrow();
            assertThat(results.start(claim)).isTrue();
            results.fail(claim, false, "DataAccessResourceFailureException");
            jdbc.update("UPDATE trip_analysis_executions SET result_next_attempt_at = NULL WHERE execution_id = ?", executionId);
        }
        AdditionalAttachmentProcessingStatusResponse response = status.findAdditionalStatus(tripId, ownerId, addition.additionId());
        assertThat(response.status()).isEqualTo(TripProcessingStatusResponse.Status.FINALIZING);
        assertThat(response.analysisExecutionId()).isEqualTo(executionId);
        assertThat(response.analysisState()).isEqualTo(TripAnalysisExecution.Status.APPLY_BLOCKED);
        assertThat(response.blockedReason()).isEqualTo("RESULT_APPLY_RETRY_EXHAUSTED");
        assertThat(status.findStatus(tripId, ownerId).analysisState()).isEqualTo(TripAnalysisExecution.Status.APPLY_BLOCKED);
    }

    @ParameterizedTest
    @EnumSource(TripSharePermission.class)
    void 접수한_사람이어도_현재_열람_권한을_잃으면_묶음_상태를_조회하지_못한다(TripSharePermission permission) {
        Long editorId = fixture.createOwner();
        shares.saveAndFlush(new TripShareUser(tripId, ownerId, editorId, permission,
                LocalDateTime.now().plusDays(1), LocalDateTime.now()));
        jdbc.update("UPDATE additional_attachment_executions SET accepted_actor_id = ? WHERE execution_id = ?",
                editorId, addition.additionId());
        assertThat(status.findAdditionalStatus(tripId, editorId, addition.additionId()).additionId()).isEqualTo(addition.additionId());
        jdbc.update("UPDATE trip_share_users SET deleted_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", tripId);
        assertThatThrownBy(() -> status.findAdditionalStatus(tripId, editorId, addition.additionId()))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void 다른_여행의_묶음과_존재하지_않는_묶음은_현재_여행에서_조회하지_못한다() {
        Long otherOwner = fixture.createOwner();
        Long otherTrip = fixture.createTrip(otherOwner);
        Addition otherAddition = fixture.prepareAddition(otherTrip, otherOwner);

        assertThatThrownBy(() -> status.findAdditionalStatus(tripId, ownerId, otherAddition.additionId()))
                .isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> status.findAdditionalStatus(otherTrip, ownerId, otherAddition.additionId()))
                .isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> status.findAdditionalStatus(tripId, ownerId, "missing-addition"))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void 재시도_대기는_분석_실패_이력이_있는_미종료_묶음에만_표시한다() {
        jdbc.update("UPDATE additional_attachment_executions SET analysis_failure_count = 1 WHERE execution_id = ?",
                addition.additionId());
        assertThat(status.findAdditionalStatus(tripId, ownerId, addition.additionId()).automaticRetryPending()).isTrue();

        scheduling.startNext(tripId).orElseThrow();
        assertThat(status.findAdditionalStatus(tripId, ownerId, addition.additionId()).automaticRetryPending()).isFalse();

        jdbc.update("""
                UPDATE additional_attachment_executions SET status = 'FAILED', ended_at = CURRENT_TIMESTAMP(6)
                WHERE execution_id = ?
                """, addition.additionId());
        assertThat(status.findAdditionalStatus(tripId, ownerId, addition.additionId()).automaticRetryPending()).isFalse();
    }

    @Test
    void 결과_수신_상태_알림은_업무_커밋과_함께_Outbox에_보존한다() {
        String executionId = scheduling.startNext(tripId).orElseThrow();
        receiveSuccess(executionId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip_edit_notification_outbox WHERE target_type = 'USER' AND target_id = ?
                  AND JSON_UNQUOTE(JSON_EXTRACT(event_json, '$.payload.message')) = 'TRIP_PROCESSING_STATUS_FOUND'
                """, Integer.class, ownerId)).isPositive();
        assertThat(jdbc.queryForObject("SELECT result_raw_payload IS NOT NULL FROM trip_analysis_executions WHERE execution_id = ?",
                Boolean.class, executionId)).isTrue();
    }

    @Test
    void 처리_중_여행을_삭제하면_새_분석과_늦은_결과_반영을_막는다() {
        String executionId = scheduling.startNext(tripId).orElseThrow();
        deletion.delete(tripId, ownerId);
        assertThat(jdbc.queryForObject("SELECT status FROM trip_analysis_executions WHERE execution_id = ?",
                String.class, executionId)).isEqualTo("CANCELED");
        receiveSuccess(executionId);
        assertThat(scheduling.startNext(tripId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_detail_places WHERE trip_id = ? AND deleted_at IS NULL",
                Integer.class, tripId)).isZero();
    }

    @Test
    void 삭제된_여행도_종료_확인을_재발견하고_취소_접수만으로_입력_보호를_해제하지_않는다() {
        String executionId = scheduling.startNext(tripId).orElseThrow();
        deletion.delete(tripId, ownerId);
        Mockito.when(remote.findStatus(tripId, executionId)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new TripPhotoAnalysisStatusResponse(tripId,
                    TripPhotoAnalysisStatusResponse.Status.PROCESSING,
                    null, null, null, null);
        });
        assertThat(scheduling.reconcilePending(tripId)).isFalse();
        Mockito.verify(remote).cancel(tripId);
        assertThat(jdbc.queryForObject("SELECT remote_terminal_confirmed_at FROM trip_analysis_executions WHERE execution_id = ?",
                LocalDateTime.class, executionId)).isNull();
        Mockito.when(remote.findStatus(tripId, executionId)).thenReturn(
                new TripPhotoAnalysisStatusResponse(tripId,
                        TripPhotoAnalysisStatusResponse.Status.CANCELED,
                        null, null, null, null));
        assertThat(scheduling.reconcilePending(tripId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT remote_terminal_confirmed_at FROM trip_analysis_executions WHERE execution_id = ?",
                LocalDateTime.class, executionId)).isNotNull();
    }

    @Test
    void 분석_진행률은_확정_입력의_장수와_포함된_묶음에만_적용한다() {
        Addition second = fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        Addition later = fixture.prepareAddition(tripId, ownerId);
        jdbc.update("UPDATE trip_analysis_executions SET process_published_at = CURRENT_TIMESTAMP(6) WHERE execution_id = ?", executionId);
        Mockito.when(remote.findStatus(tripId, executionId)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new TripPhotoAnalysisStatusResponse(tripId,
                    TripPhotoAnalysisStatusResponse.Status.PROCESSING,
                    new TripPhotoAnalysisStatusResponse.Progress(1, 2),
                    "CLUSTERING", null, null);
        });
        status.synchronizeAnalysis(executionId);
        assertThat(jdbc.queryForList("SELECT current_step FROM additional_attachment_executions WHERE execution_id IN (?, ?)",
                String.class, addition.additionId(), second.additionId())).containsOnly("CLUSTERING");
        assertThat(jdbc.queryForObject("SELECT current_step FROM additional_attachment_executions WHERE execution_id = ?",
                String.class, later.additionId())).isNull();
    }

    @Test
    void 자동_재분석까지_실패한_묶음의_종료를_여행_참여자에게도_기록한다() {
        String executionId = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            executionId = scheduling.startNext(tripId).orElseThrow();
            TripCreateResultEvent event = new TripCreateResultEvent("process_result", tripId, executionId,
                    TripCreateResultEvent.Status.FAILED, null,
                    json.valueToTree(Map.of("code", "AI_FAILED", "message", "분석 실패")));
            results.receive(event, json.writeValueAsString(event));
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip_edit_notification_outbox
                WHERE target_type = 'TRIP' AND target_id = ?
                  AND JSON_UNQUOTE(JSON_EXTRACT(event_json, '$.payload.data.status')) = 'FAILED'
                  AND JSON_UNQUOTE(JSON_EXTRACT(event_json, '$.payload.data.analysisExecutionId')) = ?
                """, Integer.class, tripId, executionId)).isOne();
    }

    private void receiveSuccess(String executionId) {
        JsonNode result = json.valueToTree(Map.of("places", List.of(), "failed", List.of(),
                "unclassified", List.of(Map.of("trip_attachment_id", addition.photoId(),
                        "region_origin", "UNKNOWN", "issue", "UNCLEAR_LOCATION"))));
        TripCreateResultEvent event = new TripCreateResultEvent("process_result", tripId, executionId,
                TripCreateResultEvent.Status.COMPLETED, result, null);
        assertThat(results.receive(event, json.writeValueAsString(event))).isTrue();
    }
}

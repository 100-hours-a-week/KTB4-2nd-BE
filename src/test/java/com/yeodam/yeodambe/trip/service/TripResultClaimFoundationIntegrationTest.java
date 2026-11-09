package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.websocket.service.request.AiTaskCompletionType;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.entity.TripDisplayStatus;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.service.response.TripAnalysisResultClaim;
import com.yeodam.yeodambe.trip.service.response.TripCompletionSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripResultClaimFoundationIntegrationTest {
    private static final String RAW = "{\"result\":{\"places\":[],\"unclassified\":[]}}";
    @Autowired
    private TripAnalysisResultService initialResults;
    @Autowired
    private AdditionalAttachmentKafkaResultService additionalResults;
    @Autowired
    private TripCreationExecutionRepository initials;
    @Autowired
    private AdditionalAttachmentExecutionRepository additions;
    @Autowired
    private TripCompletionSnapshotFactory snapshots;
    @Autowired
    private TransactionTemplate transaction;
    @Autowired
    private JdbcTemplate jdbc;
    private TripAnalysisTestFixture fixture;
    private Long ownerId;
    private Long tripId;

    @BeforeEach
    void prepareTrip() {
        fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
    }

    @AfterEach
    void finishExecutions() {
        jdbc.update("UPDATE trip_creation_executions SET status = 'CANCELED', ended_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", tripId);
        jdbc.update("UPDATE additional_attachment_executions SET status = 'CANCELED', ended_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", tripId);
    }

    @Test
    void 최초_결과는_지정한_실행만_선점하고_리스와_재시도_대기와_이전_토큰을_검증한다() {
        TripCreationExecution execution = new TripCreationExecution(UUID.randomUUID().toString(), tripId, ownerId, 1);
        execution.enqueueProcess("{}");
        execution.receiveResult(RAW, "COMPLETED", LocalDateTime.now());
        initials.saveAndFlush(execution);
        String executionId = execution.getExecutionId();
        assertThat(initialResults.findResultClaimCandidates())
                .extracting(TripCreationExecution::getExecutionId).contains(executionId);

        TripAnalysisResultClaim first = initialResults.claimResult(executionId).orElseThrow();
        assertThat(first.rawPayload()).isEqualTo(RAW);
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(initialResults.claimResult(executionId)).isEmpty();
        assertThat(initialResults.findResultClaimCandidates())
                .extracting(TripCreationExecution::getExecutionId).doesNotContain(executionId);
        jdbc.update("""
                UPDATE trip_creation_executions SET result_lease_until = CURRENT_TIMESTAMP - INTERVAL 1 SECOND,
                    result_next_attempt_at = CURRENT_TIMESTAMP + INTERVAL 1 MINUTE WHERE execution_id = ?
                """, executionId);
        assertThat(initialResults.claimResult(executionId)).isEmpty();
        jdbc.update("UPDATE trip_creation_executions SET result_next_attempt_at = NULL WHERE execution_id = ?", executionId);
        TripAnalysisResultClaim next = initialResults.claimResult(executionId).orElseThrow();
        assertThat(next.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(initialResults.startResultAttempt(first)).isFalse();
        assertThat(initialResults.startResultAttempt(next)).isTrue();
        assertThat(initials.findById(executionId).orElseThrow().getResultAttempts()).isEqualTo(1);
        jdbc.update("UPDATE trip_creation_executions SET ended_at = CURRENT_TIMESTAMP, result_lease_until = NULL WHERE execution_id = ?", executionId);
        assertThat(initialResults.claimResult(executionId)).isEmpty();
    }

    @Test
    void 추가_결과도_지정한_실행만_선점하고_리스와_재시도_대기와_이전_토큰을_검증한다() {
        AdditionalAttachmentExecution execution = addition();
        execution.receiveResult(RAW, "COMPLETED", LocalDateTime.now());
        additions.saveAndFlush(execution);
        String executionId = execution.getExecutionId();
        assertThat(additionalResults.findClaimCandidates())
                .extracting(AdditionalAttachmentExecution::getExecutionId).contains(executionId);

        TripAnalysisResultClaim first = additionalResults.claim(executionId).orElseThrow();
        assertThat(first.rawPayload()).isEqualTo(RAW);
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(additionalResults.claim(executionId)).isEmpty();
        assertThat(additionalResults.findClaimCandidates())
                .extracting(AdditionalAttachmentExecution::getExecutionId).doesNotContain(executionId);
        jdbc.update("""
                UPDATE additional_attachment_executions SET result_lease_until = CURRENT_TIMESTAMP - INTERVAL 1 SECOND,
                    result_next_attempt_at = CURRENT_TIMESTAMP + INTERVAL 1 MINUTE WHERE execution_id = ?
                """, executionId);
        assertThat(additionalResults.claim(executionId)).isEmpty();
        jdbc.update("UPDATE additional_attachment_executions SET result_next_attempt_at = NULL WHERE execution_id = ?", executionId);
        TripAnalysisResultClaim next = additionalResults.claim(executionId).orElseThrow();
        assertThat(next.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(additionalResults.start(first)).isFalse();
        assertThat(additionalResults.start(next)).isTrue();
        assertThat(additions.findById(executionId).orElseThrow().getResultAttempts()).isEqualTo(1);
        jdbc.update("UPDATE additional_attachment_executions SET ended_at = CURRENT_TIMESTAMP, result_lease_until = NULL WHERE execution_id = ?", executionId);
        assertThat(additionalResults.claim(executionId)).isEmpty();
    }

    @Test
    void 추가_만료는_지정한_실행의_현재_시각과_결과를_재검증한다() {
        AdditionalAttachmentExecution expired = additions.saveAndFlush(addition());
        AdditionalAttachmentExecution fresh = additions.saveAndFlush(addition());
        jdbc.update("UPDATE additional_attachment_executions SET last_upload_activity_at = CURRENT_TIMESTAMP - INTERVAL 11 MINUTE WHERE execution_id = ?",
                expired.getExecutionId());
        assertThat(additionalResults.findExpiredCandidates())
                .extracting(AdditionalAttachmentExecution::getExecutionId)
                .contains(expired.getExecutionId()).doesNotContain(fresh.getExecutionId());

        additionalResults.expire(fresh.getExecutionId());
        assertThat(additions.findById(fresh.getExecutionId()).orElseThrow().isTerminal()).isFalse();
        additionalResults.expire(expired.getExecutionId());
        AdditionalAttachmentExecution ended = additions.findById(expired.getExecutionId()).orElseThrow();
        assertThat(ended.getErrorCode()).isEqualTo("UPLOAD_TIMEOUT");
        assertThat(ended.isTerminal()).isTrue();
        additionalResults.expire(expired.getExecutionId());
        assertThat(additions.findById(expired.getExecutionId()).orElseThrow().getStatusVersion())
                .isEqualTo(ended.getStatusVersion());
        fresh.receiveResult(RAW, "COMPLETED", LocalDateTime.now());
        additions.saveAndFlush(fresh);
        jdbc.update("UPDATE additional_attachment_executions SET last_upload_activity_at = CURRENT_TIMESTAMP - INTERVAL 11 MINUTE WHERE execution_id = ?",
                fresh.getExecutionId());
        additionalResults.expire(fresh.getExecutionId());
        assertThat(additions.findById(fresh.getExecutionId()).orElseThrow().isTerminal()).isFalse();
    }

    @Test
    void 완료_요약은_원문_수치_대신_삭제와_미완료_묶음을_제외한_현재_DB를_반영한다() {
        Long visible = fixture.createPhoto(tripId, ownerId);
        Long deleted = fixture.createPhoto(tripId, ownerId);
        Long classified = fixture.createPhoto(tripId, ownerId);
        fixture.prepareAddition(tripId, ownerId);
        jdbc.update("""
                INSERT INTO trip_detail_places (trip_id, place_name, latitude, longitude,
                    started_at, ended_at, order_number, deleted_at)
                VALUES (?, '현재 장소', 37, 127, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, NULL),
                       (?, '삭제 장소', 37, 127, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 2, CURRENT_TIMESTAMP)
                """, tripId, tripId);
        Long placeId = jdbc.queryForObject("SELECT trip_place_id FROM trip_detail_places WHERE trip_id = ? AND deleted_at IS NULL",
                Long.class, tripId);
        jdbc.update("UPDATE trip_attachments SET classification_status = 'ACTIVE', trip_place_id = ? WHERE trip_attachment_id = ?",
                placeId, classified);
        jdbc.update("UPDATE trip_attachments SET deleted_at = CURRENT_TIMESTAMP WHERE trip_attachment_id = ?", deleted);
        jdbc.update("UPDATE trips SET change_sequence = 12, thumbnail_key = 'current-preview' WHERE trip_id = ?", tripId);

        TripCompletionSnapshot current = transaction.execute(status -> snapshots.captureCurrent(
                tripId, ownerId, AiTaskCompletionType.TRIP_ANALYSIS_APPLIED, RAW));

        assertThat(current.result().placeFolderCount()).isEqualTo(1);
        assertThat(current.result().classifiedAttachmentCount()).isEqualTo(1);
        assertThat(current.result().unclassifiedAttachmentCount()).isEqualTo(1);
        assertThat(current.listItem().sequence()).isEqualTo(12);
        assertThat(current.thumbnailKey()).isEqualTo("current-preview");
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM trip_attachments WHERE trip_attachment_id = ?",
                LocalDateTime.class, visible)).isNull();
        assertThat(transaction.execute(status -> snapshots.capture(
                tripId, ownerId, AiTaskCompletionType.TRIP_ANALYSIS_APPLIED, RAW))
                .result().unclassifiedAttachmentCount()).isZero();
    }

    @Test
    void 기존_상태_생성자는_참여자_알림을_추가하지_않고_분석_상태_생성자만_참여자를_알린다() {
        TripProcessingStateSnapshot legacy = new TripProcessingStateSnapshot(
                tripId, "legacy", 1, TripDisplayStatus.PROCESSING, null, null);
        TripProcessingStateSnapshot completed = new TripProcessingStateSnapshot(
                tripId, "legacy", 2, TripDisplayStatus.COMPLETED, null, null, RAW);
        TripProcessingStateSnapshot analysis = new TripProcessingStateSnapshot(
                tripId, "addition", 3, TripDisplayStatus.FINALIZING, null, null, RAW,
                "analysis", TripAnalysisExecution.Status.RESULT_APPLYING, null);

        assertThat(legacy.notifyParticipants()).isFalse();
        assertThat(completed.notifyParticipants()).isFalse();
        assertThat(analysis.notifyParticipants()).isTrue();
        assertThat(analysis.analysisExecutionId()).isEqualTo("analysis");
        assertThat(analysis.analysisState()).isEqualTo(TripAnalysisExecution.Status.RESULT_APPLYING);
    }

    private AdditionalAttachmentExecution addition() {
        return new AdditionalAttachmentExecution(UUID.randomUUID().toString(), tripId, ownerId, 1, LocalDateTime.now());
    }
}

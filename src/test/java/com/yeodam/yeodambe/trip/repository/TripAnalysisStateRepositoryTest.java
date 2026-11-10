package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadOutbox;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecutionUpload;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class TripAnalysisStateRepositoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 12, 0);
    @Autowired
    private TripAnalysisExecutionRepository analyses;
    @Autowired
    private TripAnalysisExecutionUploadRepository included;
    @Autowired
    private AdditionalAttachmentExecutionRepository additions;
    @Autowired
    private AdditionalAttachmentUploadOutboxRepository outboxes;
    @Autowired
    private TripCreationExecutionRepository creations;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;
    private Long ownerId;
    private Long tripId;

    @BeforeEach
    void prepareTrip() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
    }

    @Test
    void 닫힌_V3_묶음만_선택하고_활성_분석과_분리된_묶음은_다시_시작하지_않는다() {
        AdditionalAttachmentExecution legacy = addition(false, true);
        AdditionalAttachmentExecution open = addition(true, false);
        AdditionalAttachmentExecution ready = addition(true, true);
        AdditionalAttachmentExecution quarantined = addition(true, true);
        quarantined.recordAnalysisFailure(NOW);
        quarantined.recordAnalysisFailure(NOW);
        additions.flush();

        assertThat(additions.countDeclaredPendingAttachments(tripId)).isEqualTo(4);
        assertThat(additions.hasActiveLegacyExecution(tripId)).isTrue();
        assertThat(additions.findAnalysisCandidatesForUpdate(tripId))
                .extracting(AdditionalAttachmentExecution::getExecutionId).containsExactly(ready.getExecutionId());
        assertThat(additions.findAnalysisCandidateTripIdsAfter(0L, PageRequest.of(0, 10))).contains(tripId);
        assertThat(additions.findAnalysisCandidateTripIdsAfter(tripId, PageRequest.of(0, 10))).doesNotContain(tripId);
        legacy.finish(AdditionalAttachmentExecution.Status.COMPLETED, null, NOW);
        open.finish(AdditionalAttachmentExecution.Status.CANCELED, null, NOW);
        additions.flush();
        assertThat(additions.hasActiveLegacyExecution(tripId)).isFalse();
        assertThat(additions.countDeclaredPendingAttachments(tripId)).isEqualTo(2);
        assertThat(additions.findFirstByTripIdAndUserIdAndEndedAtIsNullOrderByCreatedAtDescExecutionIdDesc(
                tripId, ownerId)).isPresent();

        TripAnalysisExecution execution = analyses.saveAndFlush(new TripAnalysisExecution(id(), tripId, ownerId));
        included.saveAndFlush(new TripAnalysisExecutionUpload(execution.getExecutionId(), ready.getExecutionId()));
        assertThat(additions.findAnalysisCandidateTripIdsAfter(0L, PageRequest.of(0, 10))).doesNotContain(tripId);
        assertThat(analyses.findFirstByTripIdAndEndedAtIsNull(tripId)).isPresent();
        assertThat(analyses.findForAddition(ready.getExecutionId(), PageRequest.of(0, 1)))
                .extracting(TripAnalysisExecution::getExecutionId).containsExactly(execution.getExecutionId());
        assertThat(included.existsByExecutionIdAndAdditionId(execution.getExecutionId(), ready.getExecutionId())).isTrue();
        assertThat(included.existsByExecutionIdAndAdditionId(execution.getExecutionId(), legacy.getExecutionId())).isFalse();
    }

    @Test
    void 결과_대기와_저장_재시도는_발행_시각과_임대_만료_경계를_따른다() {
        TripAnalysisExecution execution = new TripAnalysisExecution(id(), tripId, ownerId);
        execution.recordProcessPublished(NOW);
        execution = analyses.saveAndFlush(execution);
        assertThat(analyses.findAwaitingAiStatus()).extracting(TripAnalysisExecution::getExecutionId)
                .contains(execution.getExecutionId());
        assertThat(analyses.findExpired(NOW.minusSeconds(1), PageRequest.of(0, 10))).isEmpty();
        assertThat(analyses.findExpired(NOW, PageRequest.of(0, 10)))
                .extracting(TripAnalysisExecution::getExecutionId).containsExactly(execution.getExecutionId());

        execution.receiveResult("original", "COMPLETED", NOW);
        execution.claimResult("worker", NOW.plusSeconds(30));
        analyses.flush();
        assertThat(analyses.findAwaitingAiStatus()).isEmpty();
        assertThat(analyses.findExpired(NOW.plusDays(1), PageRequest.of(0, 10))).isEmpty();
        assertThat(analyses.findResultsToApply(NOW.plusSeconds(29), PageRequest.of(0, 10))).isEmpty();
        assertThat(analyses.findResultsToApply(NOW.plusSeconds(30), PageRequest.of(0, 10)))
                .extracting(TripAnalysisExecution::getExecutionId).containsExactly(execution.getExecutionId());
        execution.retryResult("database unavailable", NOW.plusSeconds(30));
        analyses.flush();
        assertThat(analyses.findResultsToApply(NOW.plusSeconds(39), PageRequest.of(0, 10))).isEmpty();
        assertThat(analyses.findResultsToApply(NOW.plusSeconds(40), PageRequest.of(0, 10))).hasSize(1);
        execution.retryResult("second failure", NOW.plusSeconds(40));
        execution.retryResult("third failure", NOW.plusSeconds(50));
        analyses.flush();
        assertThat(analyses.findResultsToApply(NOW.plusDays(1), PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void 세_종류_실행의_입력은_로컬_취소_후에도_원격_종료_확인까지_보호한다() {
        String payload = "{\"attachments\":[{\"analyze_storage_key\":\"protected/key\"}]}";
        AdditionalAttachmentExecution legacy = addition(false, true);
        String uploadId = id();
        jdbc.update("""
                INSERT INTO additional_attachment_upload_batches
                    (upload_id, addition_id, trip_id, user_id, batch_no, total_attachment_count, is_last_batch, status)
                VALUES (?, ?, ?, ?, 1, 1, TRUE, 'PREPARED')
                """, uploadId, legacy.getExecutionId(), tripId, ownerId);
        Long batchId = jdbc.queryForObject("""
                SELECT upload_batch_id FROM additional_attachment_upload_batches WHERE upload_id = ?
                """, Long.class, uploadId);
        AdditionalAttachmentUploadOutbox legacyOutbox = outboxes.saveAndFlush(
                new AdditionalAttachmentUploadOutbox(batchId, "process", payload, id()));
        legacy.attachProcessOutbox(legacyOutbox.getId(), 1);
        TripAnalysisExecution execution = analyses.saveAndFlush(new TripAnalysisExecution(id(), tripId, ownerId));
        AdditionalAttachmentUploadOutbox analysisOutbox = outboxes.saveAndFlush(
                new AdditionalAttachmentUploadOutbox(null, execution.getExecutionId(), 0, "process", payload, id()));
        execution.attachProcessOutbox(analysisOutbox.getId());
        TripCreationExecution initial = creations.saveAndFlush(new TripCreationExecution(id(), tripId, ownerId, 1));
        initial.enqueueProcess(payload);
        entityManager.flush();
        assertThat(analyses.countActiveInputReferences("protected/key")).isEqualTo(3);
        assertThat(analyses.countActiveInputReferences("other/key")).isZero();

        legacy.finish(AdditionalAttachmentExecution.Status.CANCELED, null, NOW);
        execution.finish(TripAnalysisExecution.Status.CANCELED, null, true, NOW);
        initial.finish(TripCreationExecution.Status.CANCELED, null, NOW);
        entityManager.flush();
        entityManager.clear();
        assertThat(analyses.countActiveInputReferences("protected/key")).isEqualTo(3);
        assertThat(analyses.findUnconfirmedTripIdsAfter(0L, PageRequest.of(0, 10))).containsExactly(tripId);
        assertThat(analyses.findUnconfirmedTripIdsAfter(tripId, PageRequest.of(0, 10))).isEmpty();
        assertThat(analyses.findUnconfirmedRemoteTerminationsByTripId(tripId)).hasSize(1);
        assertThat(additions.findUnconfirmedLegacyTimeouts(tripId)).hasSize(1);
        assertThat(creations.findUnconfirmedRemoteTerminations(tripId)).hasSize(1);
        additions.findById(legacy.getExecutionId()).orElseThrow().confirmRemoteTermination(NOW);
        analyses.findById(execution.getExecutionId()).orElseThrow().confirmRemoteTermination(NOW);
        creations.findById(initial.getExecutionId()).orElseThrow().confirmRemoteTermination(NOW);
        entityManager.flush();

        assertThat(analyses.countActiveInputReferences("protected/key")).isZero();
        assertThat(analyses.findUnconfirmedTripIdsAfter(0L, PageRequest.of(0, 10))).isEmpty();
        assertThat(analyses.findUnconfirmedRemoteTerminationsByTripId(tripId)).isEmpty();
        assertThat(additions.findUnconfirmedLegacyTimeouts(tripId)).isEmpty();
        assertThat(creations.findUnconfirmedRemoteTerminations(tripId)).isEmpty();
        assertThat(outboxes.findById(analysisOutbox.getId()).orElseThrow().getExecutionBatchNo()).isZero();
        assertThat(outboxes.findById(legacyOutbox.getId()).orElseThrow().getAnalysisExecutionId()).isNull();
    }

    private AdditionalAttachmentExecution addition(boolean v3, boolean closed) {
        AdditionalAttachmentExecution execution = new AdditionalAttachmentExecution(id(), tripId, ownerId, 1, NOW);
        if (v3) {
            execution.acceptV3(ownerId);
        }
        if (closed) {
            execution.closeInput();
        }
        return additions.saveAndFlush(execution);
    }

    private String id() {
        return UUID.randomUUID().toString();
    }
}

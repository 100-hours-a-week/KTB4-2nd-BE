package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.event.handler.TripCreateResultEventHandler;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture.Addition;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LegacyAnalysisTerminalIntegrationTest {
    @Autowired
    private TripDeletionService deletion;
    @Autowired
    private TripCreateResultEventHandler initialResults;
    @Autowired
    private TripAnalysisResultApplicationService additionalResults;
    @Autowired
    private TripCreationExecutionRepository initialExecutions;
    @Autowired
    private TripAnalysisExecutionRepository inputs;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @MockitoBean
    private TripAttachmentStorageClient storage;
    private TripAnalysisTestFixture fixture;
    private Long ownerId;
    private Long tripId;
    private Long photoId;
    private String inputKey;

    @BeforeEach
    void prepareTrip() {
        fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        photoId = fixture.createPhoto(tripId, ownerId);
        inputKey = jdbc.queryForObject("SELECT analyze_storage_key FROM trip_attachments WHERE trip_attachment_id = ?",
                String.class, photoId);
    }

    @ParameterizedTest
    @EnumSource(Workflow.class)
    void 삭제된_여행의_늦은_결과는_원격_종료만_확인하고_상태를_되살리지_않는다(Workflow workflow) {
        String executionId = prepareAndDelete(workflow);
        Map<String, Object> beforeStats = jdbc.queryForMap("SELECT * FROM user_stats WHERE user_id = ?", ownerId);
        Map<String, Object> beforeExecution = executionState(workflow, executionId);
        int notifications = notificationCount();
        TripCreateResultEvent event = terminalEvent(executionId, tripId);
        String raw = json.writeValueAsString(event);

        receive(workflow, event, raw);
        LocalDateTime confirmedAt = jdbc.queryForObject("SELECT remote_terminal_confirmed_at FROM " + workflow.table
                + " WHERE execution_id = ?", LocalDateTime.class, executionId);
        receive(workflow, event, raw);

        assertThat(remoteConfirmed(workflow, executionId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT remote_terminal_confirmed_at FROM " + workflow.table
                + " WHERE execution_id = ?", LocalDateTime.class, executionId)).isEqualTo(confirmedAt);
        assertThat(jdbc.queryForObject("SELECT result_conflicts FROM " + workflow.table + " WHERE execution_id = ?",
                String.class, executionId)).isEqualTo(raw);
        assertThat(inputs.countActiveInputReferences(inputKey)).isZero();
        assertThat(executionState(workflow, executionId)).isEqualTo(beforeExecution);
        assertThat(jdbc.queryForMap("SELECT * FROM user_stats WHERE user_id = ?", ownerId)).isEqualTo(beforeStats);
        assertThat(notificationCount()).isEqualTo(notifications);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM trips WHERE trip_id = ?", Boolean.class, tripId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_attachments WHERE trip_id = ? AND deleted_at IS NULL",
                Integer.class, tripId)).isZero();
        verify(storage, never()).delete(anyString());
    }

    @ParameterizedTest
    @EnumSource(Workflow.class)
    void 다른_여행_ID의_결과는_원격_종료와_보존_원문을_변경하지_않는다(Workflow workflow) {
        String executionId = prepareAndDelete(workflow);
        Long otherTrip = fixture.createTrip(fixture.createOwner());
        TripCreateResultEvent event = terminalEvent(executionId, otherTrip);

        receive(workflow, event, json.writeValueAsString(event));

        assertThat(remoteConfirmed(workflow, executionId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT result_conflicts FROM " + workflow.table + " WHERE execution_id = ?",
                String.class, executionId)).isNull();
        assertThat(inputs.countActiveInputReferences(inputKey)).isEqualTo(1);
    }

    @Test
    void 존재하지_않는_여행의_최초_결과는_기존처럼_무시한다() {
        TripCreateResultEvent event = terminalEvent(UUID.randomUUID().toString(), Long.MAX_VALUE);

        assertThatCode(() -> initialResults.handle(event, json.writeValueAsString(event))).doesNotThrowAnyException();
    }

    private String prepareAndDelete(Workflow workflow) {
        LocalDateTime now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        String executionId;
        if (workflow == Workflow.INITIAL) {
            executionId = UUID.randomUUID().toString();
            TripCreationExecution execution = new TripCreationExecution(executionId, tripId, ownerId, 1);
            execution.enqueueProcess(inputPayload(executionId));
            execution.processSent(now);
            initialExecutions.saveAndFlush(execution);
        } else {
            Addition addition = fixture.prepareAddition(tripId, ownerId);
            executionId = addition.additionId();
            jdbc.update("""
                    INSERT INTO additional_attachment_upload_outbox (upload_batch_id, message_type, payload, request_id)
                    VALUES (?, 'process', ?, ?)
                    """, addition.batchId(), inputPayload(executionId), executionId);
            Long outboxId = jdbc.queryForObject("SELECT outbox_id FROM additional_attachment_upload_outbox WHERE upload_batch_id = ?",
                    Long.class, addition.batchId());
            jdbc.update("""
                    UPDATE additional_attachment_executions SET processing_mode = 'LEGACY', status = 'AI_QUEUED',
                        process_published_at = ?, process_outbox_id = ? WHERE execution_id = ?
                    """, now, outboxId, executionId);
        }
        deletion.delete(tripId, ownerId);
        assertThat(remoteConfirmed(workflow, executionId)).isFalse();
        assertThat(inputs.countActiveInputReferences(inputKey)).isEqualTo(1);
        return executionId;
    }

    private String inputPayload(String executionId) {
        return json.writeValueAsString(Map.of(
                "type", "process", "trip_id", tripId, "execution_id", executionId,
                "attachments", List.of(Map.of("trip_attachment_id", photoId, "analyze_storage_key", inputKey))));
    }

    private TripCreateResultEvent terminalEvent(String executionId, Long eventTripId) {
        return new TripCreateResultEvent(
                "process_result",
                eventTripId,
                executionId,
                TripCreateResultEvent.Status.CANCELED,
                null,
                null
        );
    }

    private void receive(Workflow workflow, TripCreateResultEvent event, String raw) {
        if (workflow == Workflow.INITIAL) {
            initialResults.handle(event, raw);
        } else {
            assertThat(additionalResults.receive(event, raw)).isTrue();
        }
    }

    private Map<String, Object> executionState(Workflow workflow, String id) {
        return jdbc.queryForMap("SELECT status, ended_at, status_version, result_raw_payload, result_claim_token FROM "
                + workflow.table + " WHERE execution_id = ?", id);
    }

    private boolean remoteConfirmed(Workflow workflow, String id) {
        return jdbc.queryForObject("SELECT remote_terminal_confirmed_at IS NOT NULL FROM " + workflow.table
                + " WHERE execution_id = ?", Boolean.class, id);
    }

    private int notificationCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip_edit_notification_outbox
                WHERE (target_type = 'TRIP' AND target_id = ?) OR (target_type = 'USER' AND target_id = ?)
                """, Integer.class, tripId, ownerId);
    }

    enum Workflow {
        INITIAL("trip_creation_executions"),
        ADDITIONAL("additional_attachment_executions");

        private final String table;

        Workflow(String table) {
            this.table = table;
        }
    }
}

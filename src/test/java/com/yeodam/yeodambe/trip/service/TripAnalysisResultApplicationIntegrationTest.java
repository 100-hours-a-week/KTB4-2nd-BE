package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.event.handler.TripUpdateResultEventHandler;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture.Addition;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.fixture.TripUploadTestErrorMessage;
import com.yeodam.yeodambe.trip.service.response.TripAnalysisResultClaim;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripAnalysisResultApplicationIntegrationTest {
    @Autowired
    private TripAnalysisResultApplicationService results;
    @Autowired
    private TripAnalysisExecutionService scheduling;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TripUpdateResultEventHandler listener;
    @Autowired
    private JdbcConnectionDetails database;
    @Autowired
    private TripPhotoDeletionService deletion;

    private TripAnalysisTestFixture fixture;
    private Long ownerId;
    private Long tripId;
    private Long normalPhoto;

    @BeforeEach
    void prepareTrip() {
        fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        normalPhoto = fixture.createPhoto(tripId, ownerId);
    }

    @AfterEach
    void isolatePendingClaims() {
        jdbc.update("UPDATE trip_analysis_executions SET result_next_attempt_at = '2099-01-01' WHERE trip_id = ?", tripId);
    }

    @Test
    void 확정_입력만_반영하고_늦게_준비된_사진과_최신_여행_정보를_보존한다() {
        Addition first = fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        Addition later = fixture.prepareAddition(tripId, ownerId);
        jdbc.update("UPDATE trips SET trip_name = '수정한이름', end_date = '2026-09-04' WHERE trip_id = ?", tripId);
        jdbc.update("UPDATE trip_regions SET latitude = 35.0 WHERE trip_id = ?", tripId);
        receiveSuccess(executionId);
        TripAnalysisResultClaim claim = results.claim().orElseThrow();
        assertThat(results.start(claim)).isTrue();
        JsonNode validated = results.validate(claim);
        results.complete(claim, validated, Map.of());
        assertThat(state(executionId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT trip_name FROM trips WHERE trip_id = ?", String.class, tripId))
                .isEqualTo("수정한이름");
        assertThat(jdbc.queryForObject("SELECT end_date FROM trips WHERE trip_id = ?", String.class, tripId))
                .isEqualTo("2026-09-04");
        assertThat(jdbc.queryForObject("SELECT latitude FROM trip_regions WHERE trip_id = ?", Double.class, tripId))
                .isEqualTo(35.0);
        assertThat(issue(normalPhoto)).isEqualTo("UNCLEAR_LOCATION");
        assertThat(issue(first.photoId())).isEqualTo("UNCLEAR_LOCATION");
        assertThat(issue(later.photoId())).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT status FROM additional_attachment_upload_batches WHERE upload_batch_id = ?",
                String.class, later.batchId())).isEqualTo("PREPARED");
        assertThat(scheduling.startNext(tripId)).isPresent();
    }

    @Test
    void lease를_인계받은_후_이전_worker의_저장을_차단한다() {
        fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        receiveSuccess(executionId);
        TripAnalysisResultClaim previous = results.claim().orElseThrow();
        assertThat(results.start(previous)).isTrue();
        jdbc.update("UPDATE trip_analysis_executions SET result_lease_until = '2000-01-01' WHERE execution_id = ?", executionId);
        TripAnalysisResultClaim current = results.claim().orElseThrow();
        assertThat(current.claimToken()).isNotEqualTo(previous.claimToken());
        assertThat(results.renew(previous)).isFalse();
        assertThatThrownBy(() -> results.complete(previous, results.validate(previous), Map.of()))
                .isInstanceOf(CancellationException.class);
        assertThat(results.start(current)).isTrue();
        results.complete(current, results.validate(current), Map.of());
        assertThat(state(executionId)).isEqualTo("COMPLETED");
    }

    @Test
    void 같은_결과를_여러_번_받아도_정산과_완료는_한_번이다() {
        fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        receiveSuccess(executionId);
        receiveSuccess(executionId);
        TripAnalysisResultClaim claim = results.claim().orElseThrow();
        assertThat(results.claim()).isEmpty();
        assertThat(results.start(claim)).isTrue();
        results.complete(claim, results.validate(claim), Map.of());
        long sequence = jdbc.queryForObject("SELECT change_sequence FROM trips WHERE trip_id = ?", Long.class, tripId);
        receiveSuccess(executionId);
        assertThat(results.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT change_sequence FROM trips WHERE trip_id = ?", Long.class, tripId)).isEqualTo(sequence);
    }

    @Test
    void 저장_재시도_세_번을_소진하면_AI_횟수_차감_없이_원문을_보존하고_다음_분석을_막는다() {
        Addition addition = fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        receiveSuccess(executionId);
        for (int attempt = 1; attempt <= 3; attempt++) {
            TripAnalysisResultClaim claim = results.claim().orElseThrow();
            assertThat(claim.attempt()).isEqualTo(attempt);
            assertThat(results.start(claim)).isTrue();
            results.fail(claim, false, "DataAccessResourceFailureException");
            jdbc.update("UPDATE trip_analysis_executions SET result_next_attempt_at = NULL WHERE execution_id = ?", executionId);
        }
        assertThat(state(executionId)).isEqualTo("APPLY_BLOCKED");
        assertThat(jdbc.queryForObject("SELECT ended_at IS NULL AND result_raw_payload IS NOT NULL FROM trip_analysis_executions WHERE execution_id = ?",
                Boolean.class, executionId)).isTrue();
        assertThat(failureCount(addition)).isZero();
        fixture.prepareAddition(tripId, ownerId);
        assertThat(scheduling.startNext(tripId)).isEmpty();
        assertThat(results.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM trip_attachments WHERE trip_attachment_id = ?",
                Boolean.class, normalPhoto)).isTrue();
    }

    @Test
    void 함께_실패한_묶음도_각자의_재분석_횟수에_따라_분리한다() {
        Addition exhausted = fixture.prepareAddition(tripId, ownerId);
        jdbc.update("UPDATE additional_attachment_executions SET analysis_failure_count = 1 WHERE execution_id = ?", exhausted.additionId());
        Addition retryable = fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        receiveFailure(executionId);
        assertThat(failureCount(exhausted)).isEqualTo(2);
        assertThat(failureCount(retryable)).isOne();
        assertThat(jdbc.queryForObject("SELECT quarantined_at IS NOT NULL AND ended_at IS NOT NULL FROM additional_attachment_executions WHERE execution_id = ?",
                Boolean.class, exhausted.additionId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT ended_at IS NULL FROM additional_attachment_executions WHERE execution_id = ?",
                Boolean.class, retryable.additionId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM trip_attachments WHERE trip_attachment_id = ?",
                Boolean.class, normalPhoto)).isTrue();
        String next = scheduling.startNext(tripId).orElseThrow();
        assertThat(next).isNotEqualTo(executionId);
        assertThat(jdbc.queryForList("SELECT addition_id FROM trip_analysis_execution_uploads WHERE execution_id = ?",
                String.class, next)).containsExactly(retryable.additionId());
    }

    @Test
    void 타임아웃_후_늦게_온_성공은_분류하지_않고_원격_종료만_확인한다() {
        fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        jdbc.update("UPDATE trip_analysis_executions SET process_published_at = '2000-01-01' WHERE execution_id = ?", executionId);
        results.expire();
        assertThat(state(executionId)).isEqualTo("FAILED");
        receiveSuccess(executionId);
        assertThat(issue(normalPhoto)).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT remote_terminal_confirmed_at IS NOT NULL FROM trip_analysis_executions WHERE execution_id = ?",
                Boolean.class, executionId)).isTrue();
        assertThat(scheduling.startNext(tripId)).isPresent();
    }

    @Test
    void 원문_저장이_실패하면_수신_호출도_실패하고_재전달한_원문을_저장한다() throws SQLException {
        fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        String raw = json.writeValueAsString(new TripCreateResultEvent("process_result", tripId, executionId,
                TripCreateResultEvent.Status.CANCELED, null, null));
        String trigger = "reject_result_" + tripId;
        createFailureTrigger("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON trip_analysis_executions FOR EACH ROW "
                + "BEGIN IF NEW.trip_id = " + tripId + " AND NEW.result_raw_payload IS NOT NULL THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '"
                + TripUploadTestErrorMessage.ANALYSIS_OUTBOX_FAILURE.message() + "'; END IF; END");
        try {
            assertThatThrownBy(() -> listener.consume(raw)).isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT result_raw_payload FROM trip_analysis_executions WHERE execution_id = ?",
                    String.class, executionId)).isNull();
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
        listener.consume(raw);
        assertThat(jdbc.queryForObject("SELECT result_raw_payload FROM trip_analysis_executions WHERE execution_id = ?",
                String.class, executionId)).isEqualTo(raw);
    }

    @Test
    void 완료_커밋이_실패하면_분류와_예약_삭제와_정산과_알림이_함께_롤백된다() throws SQLException {
        Addition addition = fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        receiveSuccess(executionId);
        deletion.deleteOne(ownerId, normalPhoto);
        int beforeNotifications = jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_notification_outbox WHERE target_type = 'TRIP' AND target_id = ?",
                Integer.class, tripId);
        TripAnalysisResultClaim claim = results.claim().orElseThrow();
        assertThat(results.start(claim)).isTrue();
        JsonNode validated = results.validate(claim);
        Map<String, Object> previousStats = jdbc.queryForMap("SELECT * FROM user_stats WHERE user_id = ?", ownerId);
        String trigger = "reject_completion_" + tripId;
        createFailureTrigger("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON trip_analysis_executions FOR EACH ROW "
                + "BEGIN IF NEW.trip_id = " + tripId + " AND NEW.status = 'COMPLETED' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '"
                + TripUploadTestErrorMessage.ANALYSIS_OUTBOX_FAILURE.message() + "'; END IF; END");
        try {
            assertThatThrownBy(() -> results.complete(claim, validated, Map.of()))
                    .isInstanceOf(RuntimeException.class).hasRootCauseInstanceOf(SQLException.class);
            assertThat(state(executionId)).isEqualTo("RESULT_APPLYING");
            assertThat(issue(normalPhoto)).isEqualTo("NONE");
            assertThat(jdbc.queryForObject("SELECT status FROM trip_photo_deletion_reservations WHERE attachment_id = ?",
                    String.class, normalPhoto)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_notification_outbox WHERE target_type = 'TRIP' AND target_id = ?",
                    Integer.class, tripId)).isEqualTo(beforeNotifications);
            assertThat(issue(addition.photoId())).isEqualTo("NONE");
            assertThat(jdbc.queryForMap("SELECT * FROM user_stats WHERE user_id = ?", ownerId)).isEqualTo(previousStats);
            assertThat(jdbc.queryForObject("SELECT reservation_released FROM additional_attachment_executions WHERE execution_id = ?",
                    Boolean.class, addition.additionId())).isFalse();
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
        results.complete(claim, validated, Map.of());
        assertThat(state(executionId)).isEqualTo("COMPLETED");
    }

    @Test
    void 완료_알림_저장이_실패하면_결과와_예약과_정산과_사용자_참여자_알림을_모두_롤백한다() throws SQLException {
        Addition addition = fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        receiveSuccess(executionId);
        deletion.deleteOne(ownerId, normalPhoto);
        TripAnalysisResultClaim claim = results.claim().orElseThrow();
        assertThat(results.start(claim)).isTrue();
        JsonNode validated = results.validate(claim);
        Map<String, Object> previousStats = jdbc.queryForMap("SELECT * FROM user_stats WHERE user_id = ?", ownerId);
        int userNotifications = notificationCount("USER", ownerId);
        int tripNotifications = notificationCount("TRIP", tripId);
        String trigger = "reject_completion_notification_" + tripId;
        createFailureTrigger("CREATE TRIGGER " + trigger + " BEFORE INSERT ON trip_edit_notification_outbox FOR EACH ROW "
                + "BEGIN IF NEW.target_type = 'TRIP' AND NEW.target_id = " + tripId
                + " AND JSON_UNQUOTE(JSON_EXTRACT(NEW.event_json, '$.payload.data.status')) = 'COMPLETED' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '"
                + TripUploadTestErrorMessage.NOTIFICATION_OUTBOX_FAILURE.message() + "'; END IF; END");
        try {
            assertThatThrownBy(() -> results.complete(claim, validated, Map.of()))
                    .isInstanceOf(RuntimeException.class).hasRootCauseInstanceOf(SQLException.class);
            assertThat(state(executionId)).isEqualTo("RESULT_APPLYING");
            assertThat(issue(normalPhoto)).isEqualTo("NONE");
            assertThat(issue(addition.photoId())).isEqualTo("NONE");
            assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM trip_attachments WHERE trip_attachment_id = ?",
                    Boolean.class, normalPhoto)).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM trip_photo_deletion_reservations WHERE attachment_id = ?",
                    String.class, normalPhoto)).isEqualTo("PENDING");
            assertThat(jdbc.queryForMap("SELECT * FROM user_stats WHERE user_id = ?", ownerId)).isEqualTo(previousStats);
            assertThat(jdbc.queryForObject("SELECT reservation_released FROM additional_attachment_executions WHERE execution_id = ?",
                    Boolean.class, addition.additionId())).isFalse();
            assertThat(jdbc.queryForObject("SELECT result_raw_payload FROM trip_analysis_executions WHERE execution_id = ?",
                    String.class, executionId)).isEqualTo(claim.rawPayload());
            assertThat(notificationCount("USER", ownerId)).isEqualTo(userNotifications);
            assertThat(notificationCount("TRIP", tripId)).isEqualTo(tripNotifications);
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
        results.complete(claim, validated, Map.of());
        assertThat(state(executionId)).isEqualTo("COMPLETED");
        assertThat(notificationCount("USER", ownerId)).isEqualTo(userNotifications + 1);
        assertThat(notificationCount("TRIP", tripId)).isEqualTo(tripNotifications + 2);
    }

    private int notificationCount(String targetType, Long targetId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_notification_outbox WHERE target_type = ? AND target_id = ?",
                Integer.class, targetType, targetId);
    }

    private void createFailureTrigger(String statement) throws SQLException {
        try (Connection connection = DriverManager.getConnection(database.getJdbcUrl(), "root", database.getPassword());
             Statement ddl = connection.createStatement()) {
            ddl.execute(statement);
        }
    }

    private void receiveSuccess(String executionId) {
        String input = jdbc.queryForObject("SELECT payload FROM additional_attachment_upload_outbox WHERE analysis_execution_id = ? AND message_type = 'process'",
                String.class, executionId);
        List<Map<String, Object>> photos = StreamSupport.stream(json.readTree(input).path("attachments").spliterator(), false)
                .map(photo -> Map.<String, Object>of("trip_attachment_id", photo.path("trip_attachment_id").asLong(),
                        "region_origin", "UNKNOWN", "issue", "UNCLEAR_LOCATION")).toList();
        JsonNode result = json.valueToTree(Map.of("places", List.of(), "unclassified", photos, "failed", List.of()));
        receive(new TripCreateResultEvent("process_result", tripId, executionId,
                TripCreateResultEvent.Status.COMPLETED, result, null));
    }

    private void receiveFailure(String executionId) {
        receive(new TripCreateResultEvent("process_result", tripId, executionId,
                TripCreateResultEvent.Status.FAILED, null, json.valueToTree(Map.of("code", "FAILED"))));
    }

    private void receive(TripCreateResultEvent event) {
        assertThat(results.receive(event, json.writeValueAsString(event))).isTrue();
    }

    private String state(String executionId) {
        return jdbc.queryForObject("SELECT status FROM trip_analysis_executions WHERE execution_id = ?", String.class, executionId);
    }

    private int failureCount(Addition addition) {
        return jdbc.queryForObject("SELECT analysis_failure_count FROM additional_attachment_executions WHERE execution_id = ?", Integer.class, addition.additionId());
    }

    private String issue(Long photoId) {
        return jdbc.queryForObject("SELECT issue FROM trip_attachments WHERE trip_attachment_id = ?", String.class, photoId);
    }
}

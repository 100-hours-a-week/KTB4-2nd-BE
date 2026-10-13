package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class AdditionalAttachmentExecutionSchemaTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;

    private Long userId;
    private Long tripId;

    @BeforeEach
    void setUp() {
        userId = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "execution"))
                .getUserId();
        tripId = trips.saveAndFlush(new Trip(userId, "execution", LocalDate.now(), LocalDate.now()))
                .getId();
    }

    @Test
    void storesDefaultsAndAllowsMultipleExecutionsForOneTrip() {
        String first = insertExecution(tripId, userId, 1);
        insertExecution(tripId, userId, 200);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '20' AND success = 1",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM additional_attachment_executions WHERE trip_id = ?",
                Integer.class, tripId)).isEqualTo(2);
        var row = jdbc.queryForMap(
                "SELECT * FROM additional_attachment_executions WHERE execution_id = ?", first);
        assertThat(row.get("status")).isEqualTo("UPLOADING");
        assertThat(row.get("input_closed")).isEqualTo(false);
        assertThat(row.get("reserved_bytes")).isEqualTo(0L);
        assertThat(row.get("accounted_bytes")).isEqualTo(0L);
        assertThat(row.get("reservation_released")).isEqualTo(false);
        assertThat(row.get("result_attempts")).isEqualTo(0);
        assertThat(row.get("analysis_attachment_count")).isNull();
        assertThat(row.get("process_outbox_id")).isNull();
        assertThat(row.get("result_raw_payload")).isNull();
        assertThat(row.get("ended_at")).isNull();
        assertThat(row.get("last_upload_activity_at")).isNotNull();
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO additional_attachment_executions
                    (execution_id, trip_id, user_id, total_attachment_count)
                VALUES (?, ?, ?, 1)
                """, first, tripId, userId)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void keepsNewAndAnalysisCountsSeparateAndStoresLargeResult() {
        String executionId = insertExecution(tripId, userId, 20);
        String payload = "{\"result\":\"" + "x".repeat(70_000) + "\"}";
        jdbc.update("""
                UPDATE additional_attachment_executions
                SET analysis_attachment_count = 50, result_raw_payload = ?
                WHERE execution_id = ?
                """, payload, executionId);
        var row = jdbc.queryForMap("""
                SELECT total_attachment_count, analysis_attachment_count, result_raw_payload
                FROM additional_attachment_executions WHERE execution_id = ?
                """, executionId);
        assertThat(row.get("total_attachment_count")).isEqualTo(20);
        assertThat(row.get("analysis_attachment_count")).isEqualTo(50);
        assertThat(row.get("result_raw_payload")).isEqualTo(payload);
    }

    @Test
    void enforcesTripUserAndOutboxReferences() {
        assertThatThrownBy(() -> insertExecution(Long.MAX_VALUE, userId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertExecution(tripId, Long.MAX_VALUE, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        String executionId = insertExecution(tripId, userId, 1);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE additional_attachment_executions
                SET process_outbox_id = ? WHERE execution_id = ?
                """, Long.MAX_VALUE, executionId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 201})
    void rejectsInvalidNewCount(int count) {
        assertThatThrownBy(() -> insertExecution(tripId, userId, count))
                .isInstanceOfAny(DataIntegrityViolationException.class, UncategorizedSQLException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 201})
    void rejectsInvalidAnalysisCount(int count) {
        String executionId = insertExecution(tripId, userId, 1);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE additional_attachment_executions
                SET analysis_attachment_count = ? WHERE execution_id = ?
                """, count, executionId))
                .isInstanceOfAny(DataIntegrityViolationException.class, UncategorizedSQLException.class);
    }

    @Test
    void rejectsNegativeReservedAndAccountedBytes() {
        String executionId = insertExecution(tripId, userId, 1);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE additional_attachment_executions
                SET reserved_bytes = -1 WHERE execution_id = ?
                """, executionId))
                .isInstanceOfAny(DataIntegrityViolationException.class, UncategorizedSQLException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE additional_attachment_executions
                SET accounted_bytes = -1 WHERE execution_id = ?
                """, executionId))
                .isInstanceOfAny(DataIntegrityViolationException.class, UncategorizedSQLException.class);
    }

    private String insertExecution(Long tripId, Long userId, int count) {
        String executionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO additional_attachment_executions
                    (execution_id, trip_id, user_id, total_attachment_count)
                VALUES (?, ?, ?, ?)
                """, executionId, tripId, userId, count);
        return executionId;
    }
}

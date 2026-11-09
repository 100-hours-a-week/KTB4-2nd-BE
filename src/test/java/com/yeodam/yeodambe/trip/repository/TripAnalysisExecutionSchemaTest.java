package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class TripAnalysisExecutionSchemaTest {
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;
    @Autowired
    private TripRepository trips;

    private Long ownerId;
    private Long tripId;

    @BeforeEach
    void prepareTrip() {
        ownerId = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "분석회원"))
                .getUserId();
        tripId = trips.saveAndFlush(new Trip(ownerId, "분석여행", LocalDate.now(), LocalDate.now()))
                .getId();
    }

    @Test
    void 분석_실행과_포함_묶음과_삭제_예약_테이블을_생성한다() {
        Integer tableCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('trip_analysis_executions',
                                     'trip_analysis_execution_uploads',
                                     'trip_photo_deletion_reservations')
                """, Integer.class);
        assertThat(tableCount).isEqualTo(3);
    }

    @Test
    void 미종료_분석은_여행당_하나만_저장한다() {
        String first = insertAnalysis();
        assertThatThrownBy(this::insertAnalysis).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE trip_analysis_executions SET status = 'APPLY_BLOCKED' WHERE execution_id = ?", first);
        assertThatThrownBy(this::insertAnalysis).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                UPDATE trip_analysis_executions SET status = 'FAILED', ended_at = CURRENT_TIMESTAMP(6)
                WHERE execution_id = ?
                """, first);
        String second = insertAnalysis();
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void 한_묶음의_재분석_이력을_서로_다른_실행에_보존한다() {
        String additionId = insertLegacyAddition();
        String first = insertAnalysis();
        jdbc.update("""
                INSERT INTO trip_analysis_execution_uploads (execution_id, addition_id)
                VALUES (?, ?)
                """, first, additionId);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO trip_analysis_execution_uploads (execution_id, addition_id)
                VALUES (?, ?)
                """, first, additionId)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                UPDATE trip_analysis_executions SET status = 'FAILED', ended_at = CURRENT_TIMESTAMP(6)
                WHERE execution_id = ?
                """, first);
        jdbc.update("""
                INSERT INTO trip_analysis_execution_uploads (execution_id, addition_id)
                VALUES (?, ?)
                """, insertAnalysis(), additionId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip_analysis_execution_uploads WHERE addition_id = ?
                """, Integer.class, additionId)).isEqualTo(2);
    }

    @Test
    void 기존_묶음은_유지하고_새_분석_Outbox는_배치_없이_저장한다() {
        String legacyId = insertLegacyAddition();
        String executionId = insertAnalysis();
        jdbc.update("""
                INSERT INTO additional_attachment_upload_outbox
                    (analysis_execution_id, execution_batch_no, message_type, payload, request_id, trip_id)
                VALUES (?, 0, 'process', '{"attachments":[]}', 'frozen-request', ?)
                """, executionId, tripId);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT processing_mode, accepted_actor_id, analysis_failure_count
                FROM additional_attachment_executions WHERE execution_id = ?
                """, legacyId);
        assertThat(row.get("processing_mode")).isEqualTo("LEGACY");
        assertThat(row.get("analysis_failure_count")).isEqualTo(0);
        assertThat(jdbc.queryForObject("""
                SELECT payload FROM additional_attachment_upload_outbox WHERE analysis_execution_id = ?
                """, String.class, executionId)).isEqualTo("{\"attachments\":[]}");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO additional_attachment_upload_outbox
                    (analysis_execution_id, execution_batch_no, message_type, payload, request_id)
                VALUES (?, 0, 'process', '{}', 'duplicate')
                """, executionId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 원격_종료_미확인을_로컬_종료_이후에도_보존한다() {
        String executionId = insertAnalysis();
        jdbc.update("""
                UPDATE trip_analysis_executions
                SET status = 'FAILED', ended_at = CURRENT_TIMESTAMP(6),
                    requires_remote_termination_confirmation = TRUE
                WHERE execution_id = ?
                """, executionId);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT requires_remote_termination_confirmation, remote_terminal_confirmed_at
                FROM trip_analysis_executions WHERE execution_id = ?
                """, executionId);
        assertThat(row.get("requires_remote_termination_confirmation")).isEqualTo(true);
        assertThat(row.get("remote_terminal_confirmed_at")).isNull();
    }

    private String insertAnalysis() {
        String executionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO trip_analysis_executions (execution_id, trip_id, owner_id)
                VALUES (?, ?, ?)
                """, executionId, tripId, ownerId);
        return executionId;
    }

    private String insertLegacyAddition() {
        String additionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO additional_attachment_executions
                    (execution_id, trip_id, user_id, total_attachment_count)
                VALUES (?, ?, ?, 1)
                """, additionId, tripId, ownerId);
        return additionId;
    }
}

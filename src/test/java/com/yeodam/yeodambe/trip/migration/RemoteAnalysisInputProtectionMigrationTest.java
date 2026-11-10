package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.SharedTestMysql;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteAnalysisInputProtectionMigrationTest {
    @Test
    void 기존_실패와_취소_실행은_원문_없는_원격_요청만_보호하고_재실행해도_보존한다() throws Exception {
        JdbcConnectionDetails database = SharedTestMysql.createDatabase();
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        configuration.target("37").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        jdbc.update("INSERT INTO users (user_id, email, nickname) VALUES (90000, 'remote@test.local', '회원')");
        List<String> states = List.of("FAILED", "CANCELED", "COMPLETED", "FAILED", "CANCELED", "AI_QUEUED");
        for (int index = 0; index < states.size(); index++) {
            long tripId = index + 90001L;
            jdbc.update("""
                    INSERT INTO trips (trip_id, user_id, trip_name, start_date, end_date)
                    VALUES (?, 90000, '기존여행', '2026-11-09', '2026-11-09')
                    """, tripId);
            jdbc.update("""
                    INSERT INTO trip_creation_executions
                        (execution_id, trip_id, user_id, total_attachment_count, status,
                         process_payload, result_raw_payload, ended_at)
                    VALUES (?, ?, 90000, 1, ?, ?, ?, ?)
                    """, "initial-" + index, tripId, states.get(index), index == 4 ? null : "{}",
                    index == 3 ? "original result" : null, index == 5 ? null : "2026-11-09 12:00:00");
        }
        jdbc.update("""
                INSERT INTO additional_attachment_executions
                    (execution_id, trip_id, user_id, total_attachment_count)
                VALUES ('legacy', 90001, 90000, 1)
                """);

        Flyway flyway = configuration.target("40").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                SELECT execution_id FROM trip_creation_executions
                WHERE requires_remote_termination_confirmation = TRUE ORDER BY execution_id
                """, String.class)).containsExactly("initial-0", "initial-1");
        assertThat(jdbc.queryForObject("""
                SELECT result_raw_payload FROM trip_creation_executions WHERE execution_id = 'initial-3'
                """, String.class)).isEqualTo("original result");
        assertThat(jdbc.queryForObject("""
                SELECT remote_terminal_confirmed_at FROM additional_attachment_executions WHERE execution_id = 'legacy'
                """, Object.class)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT processing_mode FROM additional_attachment_executions WHERE execution_id = 'legacy'
                """, String.class)).isEqualTo("LEGACY");
        assertThat(jdbc.queryForObject("""
                SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trip_edit_notification_outbox'
                  AND COLUMN_NAME = 'thumbnail_key'
                """, Long.class)).isEqualTo(500L);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}

package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.SharedTestMysql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingStatusVersionStartMigrationTest {
    @Test
    void advancesExistingVersionsOnceAndStartsNewRowsAtOne() throws Exception {
        var database = SharedTestMysql.createDatabase();
        var configuration = Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        configuration.target("28").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        jdbc.update("INSERT INTO users (user_id, email, nickname) VALUES (89000, 'start@test.local', 'version')");
        for (long tripId = 89001; tripId <= 89003; tripId++) {
            jdbc.update("""
                    INSERT INTO trips (trip_id, user_id, trip_name, start_date, end_date)
                    VALUES (?, 89000, 'version', '2026-10-29', '2026-10-29')
                    """, tripId);
        }
        var executionTables = List.of("trip_creation_executions", "additional_attachment_executions");
        for (String table : executionTables) {
            jdbc.update("INSERT INTO " + table + " (execution_id, trip_id, user_id, total_attachment_count)"
                    + " VALUES ('initial', 89001, 89000, 1)");
            jdbc.update("INSERT INTO " + table + " (execution_id, trip_id, user_id, total_attachment_count,"
                    + " status, display_status, current_step, error_code, status_version)"
                    + " VALUES ('changed', 89002, 89000, 1, 'FAILED', 'FAILED', NULL, 'AI_PROCESSING_FAILED', 7)");
        }
        jdbc.update("""
                INSERT INTO story_generation_jobs (execution_id, trip_id, user_id, mood)
                VALUES ('initial', 89001, 89000, 'PLAIN')
                """);
        jdbc.update("""
                INSERT INTO story_generation_jobs
                    (execution_id, trip_id, user_id, mood, status, status_version, error_code)
                VALUES ('changed', 89002, 89000, 'PLAIN', 'FAILED', 7, 'STORY_GENERATION_FAILED')
                """);

        var flyway = configuration.target("29").load();
        flyway.migrate();
        var tables = List.of("trip_creation_executions", "additional_attachment_executions", "story_generation_jobs");
        for (String table : tables) {
            assertThat(jdbc.queryForObject("SELECT status_version FROM " + table + " WHERE execution_id='initial'", Long.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status_version FROM " + table + " WHERE execution_id='changed'", Long.class))
                    .isEqualTo(8);
            var row = jdbc.queryForMap("SELECT * FROM " + table + " WHERE execution_id='changed'");
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("error_code")).isEqualTo(table.equals("story_generation_jobs")
                    ? "STORY_GENERATION_FAILED" : "AI_PROCESSING_FAILED");
        }
        for (String table : executionTables) {
            jdbc.update("INSERT INTO " + table + " (execution_id, trip_id, user_id, total_attachment_count)"
                    + " VALUES ('new', 89003, 89000, 1)");
        }
        jdbc.update("""
                INSERT INTO story_generation_jobs (execution_id, trip_id, user_id, mood)
                VALUES ('new', 89003, 89000, 'PLAIN')
                """);
        for (String table : tables) {
            assertThat(jdbc.queryForObject("SELECT status_version FROM " + table + " WHERE execution_id='new'", Long.class))
                    .isEqualTo(1);
        }

        assertThat(flyway.migrate().migrationsExecuted).isZero();
        for (String table : tables) {
            assertThat(jdbc.queryForObject("SELECT status_version FROM " + table + " WHERE execution_id='changed'", Long.class))
                    .isEqualTo(8);
        }
    }
}

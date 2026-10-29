package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.SharedTestMysql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingStatusVersionMigrationTest {

    @Test
    void migratesExistingStatesAndInitializesNewWorkAtVersionZero() throws Exception {
        var database = SharedTestMysql.createDatabase();
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .target("27").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        jdbc.update("INSERT INTO users (user_id, email, nickname) VALUES (89000, 'version@test.local', 'version')");

        List<String> states = List.of("UPLOADING", "IMAGE_PROCESSING", "AI_QUEUED",
                "RESULT_APPLYING", "COMPLETED", "FAILED", "CANCELED");
        List<String> tables = List.of("trip_creation_executions", "additional_attachment_executions");
        for (int i = 0; i < states.size(); i++) {
            long tripId = 89001L + i;
            jdbc.update("""
                    INSERT INTO trips (trip_id, user_id, trip_name, start_date, end_date)
                    VALUES (?, 89000, 'version', '2026-10-29', '2026-10-29')
                    """, tripId);
            for (String table : tables) {
                jdbc.update("INSERT INTO " + table
                                + " (execution_id, trip_id, user_id, status, total_attachment_count) VALUES (?, ?, 89000, ?, 1)",
                        "execution-" + i, tripId, states.get(i));
            }
        }
        jdbc.update("""
                INSERT INTO story_generation_jobs (execution_id, trip_id, user_id, mood)
                VALUES ('story-execution', 89001, 89000, 'PLAIN')
                """);

        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .target("28").load().migrate();

        for (String table : tables) {
            for (int i = 0; i < states.size(); i++) {
                var row = jdbc.queryForMap("SELECT * FROM " + table + " WHERE execution_id = ?", "execution-" + i);
                String expected = switch (states.get(i)) {
                    case "RESULT_APPLYING" -> "FINALIZING";
                    case "COMPLETED", "FAILED", "CANCELED" -> states.get(i);
                    default -> "PROCESSING";
                };
                assertThat(row.get("status")).isEqualTo(states.get(i));
                assertThat(row.get("display_status")).isEqualTo(expected);
                assertThat(row.get("current_step")).isNull();
                assertThat(row.get("status_version")).isEqualTo(0L);
            }
        }
        assertThat(jdbc.queryForObject("SELECT status_version FROM story_generation_jobs WHERE execution_id = 'story-execution'",
                Long.class)).isZero();

        jdbc.update("""
                INSERT INTO trips (trip_id, user_id, trip_name, start_date, end_date)
                VALUES (89008, 89000, 'new', '2026-10-29', '2026-10-29')
                """);
        for (String table : tables) {
            jdbc.update("INSERT INTO " + table
                    + " (execution_id, trip_id, user_id, total_attachment_count) VALUES ('new-execution', 89008, 89000, 1)");
            var row = jdbc.queryForMap("SELECT * FROM " + table + " WHERE execution_id = 'new-execution'");
            assertThat(row.get("display_status")).isEqualTo("PROCESSING");
            assertThat(row.get("status_version")).isEqualTo(0L);
            assertThat(row.get("current_step")).isNull();
        }
    }
}

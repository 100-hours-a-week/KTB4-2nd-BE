package com.yeodam.yeodambe.integration.migration;

import com.yeodam.yeodambe.SharedTestMysql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxCdcMigrationTest {
    @Test
    void retainsExistingIdsPayloadsAndPublicationWhileBackfillingTripId() throws Exception {
        var database = SharedTestMysql.createDatabase();
        var flyway = Flyway.configure().dataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
        flyway.target("23").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        jdbc.update("INSERT INTO users (user_id, email, nickname) VALUES (901, 'polling@cdc.test', 'CDC')");
        jdbc.update("INSERT INTO trips (trip_id, user_id, trip_name, start_date, end_date) "
                + "VALUES (901, 901, 'polling', '2026-10-15', '2026-10-15')");
        jdbc.update("INSERT INTO story_generation_jobs (generation_id, execution_id, trip_id, user_id, mood) "
                + "VALUES (901, 'polling-story', 901, 901, 'PLAIN')");
        jdbc.update("INSERT INTO story_generation_outbox "
                + "(outbox_id, generation_id, payload, request_id, status, published_at) "
                + "VALUES (901, 901, '{\"type\":\"story\"}', 'polling-request', 'PUBLISHED', '2026-10-15 10:00:00')");
        jdbc.update("INSERT INTO additional_attachment_upload_batches "
                + "(upload_batch_id, upload_id, addition_id, trip_id, user_id, batch_no, total_attachment_count, is_last_batch) "
                + "VALUES (901, 'polling-upload', 'polling-addition', 901, 901, 1, 1, TRUE)");
        jdbc.update("INSERT INTO additional_attachment_upload_outbox "
                + "(outbox_id, upload_batch_id, message_type, payload, request_id) "
                + "VALUES (901, 901, 'photos_ready', '{\"type\":\"photos_ready\"}', 'polling-request')");
        flyway.target("latest").load().migrate();
        assertThat(jdbc.queryForObject("SELECT trip_id FROM story_generation_outbox WHERE outbox_id = 901", Long.class))
                .isEqualTo(901L);
        assertThat(jdbc.queryForObject("SELECT trip_id FROM additional_attachment_upload_outbox WHERE outbox_id = 901", Long.class))
                .isEqualTo(901L);
        assertThat(jdbc.queryForObject("SELECT payload FROM story_generation_outbox WHERE outbox_id = 901", String.class))
                .isEqualTo("{\"type\":\"story\"}");
        assertThat(jdbc.queryForObject("SELECT status FROM story_generation_outbox WHERE outbox_id = 901", String.class))
                .isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT published_at FROM story_generation_outbox WHERE outbox_id = 901", java.time.LocalDateTime.class))
                .isEqualTo(java.time.LocalDateTime.of(2026, 10, 15, 10, 0));
        assertThat(jdbc.queryForObject("SELECT cdc_managed FROM story_generation_outbox WHERE outbox_id = 901", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT cdc_managed FROM additional_attachment_upload_outbox WHERE outbox_id = 901", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_creation_outbox", Integer.class)).isZero();
        jdbc.update("INSERT INTO trip_creation_executions "
                + "(execution_id, trip_id, user_id, total_attachment_count) "
                + "VALUES ('cdc-migration-execution', 901, 901, 1)");
        insertCreationOutbox(jdbc, "first", "cdc-migration-execution", "photos_ready");
        assertThatThrownBy(() -> insertCreationOutbox(jdbc, "duplicate", "cdc-migration-execution", "photos_ready"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCreationOutbox(jdbc, "missing", "missing-execution", "process"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCreationOutbox(jdbc, "invalid", "cdc-migration-execution", "unsupported"))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_trip_creation_outbox_type");
    }

    private void insertCreationOutbox(JdbcTemplate jdbc, String id, String executionId, String messageType) {
        jdbc.update("INSERT INTO trip_creation_outbox "
                        + "(outbox_id, execution_id, message_type, batch_no, trip_id, topic, payload, request_id) "
                        + "VALUES (?, ?, ?, 1, 901, 'existing.create', '{}', 'migration-request')",
                id, executionId, messageType);
    }
}

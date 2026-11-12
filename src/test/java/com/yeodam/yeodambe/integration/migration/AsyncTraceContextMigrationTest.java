package com.yeodam.yeodambe.integration.migration;

import com.yeodam.yeodambe.SharedTestMysql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncTraceContextMigrationTest {
    @Test
    void 기존_Outbox와_실행을_보존하며_선택적_추적_컬럼을_추가한다() throws Exception {
        JdbcConnectionDetails database = SharedTestMysql.createDatabase();
        Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .target("40").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        jdbc.update("INSERT INTO users (user_id, email, nickname) VALUES (901, 'trace@test.invalid', 'trace')");
        jdbc.update("INSERT INTO trips (trip_id, user_id, trip_name, start_date, end_date) "
                + "VALUES (901, 901, 'trace', '2026-11-11', '2026-11-11')");
        jdbc.update("INSERT INTO trip_creation_executions (execution_id, trip_id, user_id, total_attachment_count) "
                + "VALUES ('trace-job', 901, 901, 1)");
        jdbc.update("INSERT INTO trip_creation_outbox "
                + "(outbox_id, execution_id, message_type, batch_no, trip_id, topic, payload, request_id) "
                + "VALUES ('trace-outbox', 'trace-job', 'process', 0, 901, 'requests', '{}', 'original-request')");
        Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load().migrate();
        assertThat(jdbc.queryForMap("SELECT payload, request_id, traceparent, tracestate "
                + "FROM trip_creation_outbox WHERE outbox_id = 'trace-outbox'"))
                .containsEntry("payload", "{}").containsEntry("request_id", "original-request")
                .containsEntry("traceparent", null).containsEntry("tracestate", null);
        assertThat(jdbc.queryForMap("SELECT result_traceparent, result_tracestate "
                + "FROM trip_creation_executions WHERE execution_id = 'trace-job'"))
                .containsEntry("result_traceparent", null).containsEntry("result_tracestate", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name IN ('trip_creation_outbox', "
                + "'additional_attachment_upload_outbox', 'story_generation_outbox', 'attachment_conversion_outbox') "
                + "AND is_nullable = 'YES' AND ((column_name = 'traceparent' AND character_maximum_length = 55) "
                + "OR (column_name = 'tracestate' AND character_maximum_length = 512))", Integer.class))
                .isEqualTo(8);
    }
}

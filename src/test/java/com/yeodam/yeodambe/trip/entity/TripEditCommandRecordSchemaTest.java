package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.SharedTestMysql;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripEditCommandRecordSchemaTest {
    @Test
    void 기존_회원과_여행을_보존하며_명령_기록을_추가한다() throws Exception {
        JdbcConnectionDetails details = SharedTestMysql.createDatabase();
        migrate(details, "30");
        JdbcTemplate jdbc = jdbc(details);
        jdbc.update("INSERT INTO users(user_id, email, nickname) VALUES(42, 'record@test.com', '회원')");
        jdbc.update("""
                INSERT INTO trips(trip_id, user_id, trip_name, start_date, end_date)
                VALUES(73, 42, '기존여행', '2026-10-29', '2026-10-29')
                """);
        migrate(details, "31");

        verifySchema(jdbc);
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE user_id = 42", String.class))
                .isEqualTo("회원");
        assertThat(jdbc.queryForObject("SELECT trip_name FROM trips WHERE trip_id = 73", String.class))
                .isEqualTo("기존여행");
        insert(jdbc, 42L, "req-A");
        insert(jdbc, 42L, "req-a");
        insert(jdbc, 43L, "req-A");
        assertThatThrownBy(() -> insert(jdbc, 42L, "req-A"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_command_records", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void 엔티티로도_기록_컬럼과_유일키_만료인덱스를_생성한다() throws Exception {
        JdbcConnectionDetails details = SharedTestMysql.createDatabase();
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", details.getJdbcUrl())
                .applySetting("hibernate.connection.username", details.getUsername())
                .applySetting("hibernate.connection.password", details.getPassword())
                .applySetting("hibernate.hbm2ddl.auto", "create-only")
                .applySetting("hibernate.hbm2ddl.halt_on_error", true)
                .build();
        try (SessionFactory factory = new MetadataSources(registry)
                .addAnnotatedClass(TripEditCommandRecord.class).buildMetadata().buildSessionFactory()) {
            verifySchema(jdbc(details));
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private void verifySchema(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("""
                SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trip_edit_command_records'
                  AND COLUMN_NAME IN ('command_record_id', 'user_id', 'trip_id')
                """, String.class)).hasSize(3).allMatch(type -> type.equalsIgnoreCase("bigint unsigned"));
        assertThat(jdbc.queryForObject("""
                SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trip_edit_command_records'
                  AND INDEX_NAME = 'uk_trip_edit_command_user_request' AND NON_UNIQUE = 0
                """, String.class)).isEqualTo("user_id,request_id");
        assertThat(jdbc.queryForObject("""
                SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trip_edit_command_records'
                  AND INDEX_NAME = 'idx_trip_edit_command_expiry'
                """, String.class)).isEqualTo("result_expires_at");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trip_edit_command_records'
                  AND COLUMN_NAME IN ('completed_at', 'result_expires_at')
                  AND COLUMN_TYPE = 'datetime(6)' AND IS_NULLABLE = 'NO'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trip_edit_command_records'
                  AND COLUMN_NAME = 'result_json'
                """, String.class)).isEqualTo("YES");
    }

    private void insert(JdbcTemplate jdbc, Long userId, String requestId) {
        jdbc.update("""
                INSERT INTO trip_edit_command_records
                  (user_id, trip_id, request_id, command_type, payload_hash, result_json, completed_at, result_expires_at)
                VALUES (?, 73, ?, 'UPDATE_TRIP', ?, '{"message":"TRIP_UPDATED"}',
                        '2026-10-29 12:00:00', '2026-10-30 12:00:00')
                """, userId, requestId, "a".repeat(64));
    }

    private void migrate(JdbcConnectionDetails details, String target) {
        Flyway.configure().dataSource(details.getJdbcUrl(), details.getUsername(), details.getPassword())
                .target(target).load().migrate();
    }

    private JdbcTemplate jdbc(JdbcConnectionDetails details) {
        return new JdbcTemplate(new DriverManagerDataSource(
                details.getJdbcUrl(), details.getUsername(), details.getPassword()));
    }
}

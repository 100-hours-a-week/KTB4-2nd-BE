package com.yeodam.yeodambe.trip.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AttachmentStorageSizeMigrationTest {
    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:9.7.2");

    @Test
    void 기존_사진을_유지하고_미확인_용량과_BIGINT_실제_용량을_저장한다() {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .target("8")
                .load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        jdbc.update("""
                INSERT INTO trips (trip_id, user_id, trip_name, start_date, end_date)
                VALUES (9001, 1, '용량검증', '2026-10-02', '2026-10-02')
                """);
        jdbc.update("""
                INSERT INTO files (file_id, user_id, original_file_name, object_key, mime_type)
                VALUES (9001, 1, 'photo.jpg', 'original/photo.jpg', 'image/jpeg')
                """);
        jdbc.update("""
                INSERT INTO trip_attachments (trip_attachment_id, trip_id, file_id,
                    region_origin, issue, analyze_storage_key, preview_storage_key)
                VALUES (9001, 9001, 9001, 'UNKNOWN', 'NONE', 'analyze/photo.jpg', 'preview/photo.webp')
                """);

        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .target("11")
                .load().migrate();

        assertThat(jdbc.queryForObject("SELECT object_key FROM files WHERE file_id = 9001", String.class))
                .isEqualTo("original/photo.jpg");
        assertThat(jdbc.queryForObject("SELECT original_size_bytes FROM files WHERE file_id = 9001", Long.class))
                .isNull();
        assertThat(jdbc.queryForMap("""
                SELECT analyze_size_bytes, preview_size_bytes, display_size_bytes
                FROM trip_attachments WHERE trip_attachment_id = 9001
                """).values()).containsOnlyNulls();

        long size = 3_000_000_000L;
        jdbc.update("UPDATE files SET original_size_bytes = ? WHERE file_id = 9001", size);
        jdbc.update("""
                UPDATE trip_attachments
                SET analyze_size_bytes = ?, preview_size_bytes = ?, display_size_bytes = ?
                WHERE trip_attachment_id = 9001
                """, size, size + 1, size + 2);
        assertThat(jdbc.queryForObject("SELECT original_size_bytes FROM files WHERE file_id = 9001", Long.class))
                .isEqualTo(size);
        assertThat(jdbc.queryForMap("""
                SELECT analyze_size_bytes, preview_size_bytes, display_size_bytes
                FROM trip_attachments WHERE trip_attachment_id = 9001
                """).values()).containsExactlyInAnyOrder(size, size + 1, size + 2);
    }
}

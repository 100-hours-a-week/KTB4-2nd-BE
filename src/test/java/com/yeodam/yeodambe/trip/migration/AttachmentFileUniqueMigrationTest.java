package com.yeodam.yeodambe.trip.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class AttachmentFileUniqueMigrationTest {
    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:9.7.2");
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .cleanDisabled(false).load().clean();
        migrate("11");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        jdbc.update("""
                INSERT INTO trips (trip_id, user_id, trip_name, start_date, end_date)
                VALUES (9001, 1, '제약검증', '2026-10-08', '2026-10-08')
                """);
        jdbc.update("""
                INSERT INTO files (file_id, user_id, original_file_name, object_key, mime_type)
                VALUES (9001, 1, 'photo.jpg', 'original/photo.jpg', 'image/jpeg')
                """);
        insertAttachment(9001);
    }

    @Test
    void 기존_첨부를_유지하면서_같은_파일의_중복_첨부를_거절한다() {
        migrate("12");
        assertThat(jdbc.queryForObject(
                "SELECT file_id FROM trip_attachments WHERE trip_attachment_id = 9001", Long.class))
                .isEqualTo(9001L);
        assertThrows(DuplicateKeyException.class, () -> insertAttachment(9002));
    }

    @Test
    void 소프트_삭제된_첨부의_파일도_재사용을_거절한다() {
        jdbc.update("UPDATE trip_attachments SET deleted_at = CURRENT_TIMESTAMP WHERE trip_attachment_id = 9001");
        migrate("12");
        assertThrows(DuplicateKeyException.class, () -> insertAttachment(9002));
    }

    @Test
    void 기존_파일_중복이_있으면_마이그레이션을_중단하고_자료를_지우지_않는다() {
        insertAttachment(9002);
        assertThrows(org.flywaydb.core.api.FlywayException.class, () -> migrate("12"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM trip_attachments WHERE file_id = 9001", Long.class))
                .isEqualTo(2L);
    }

    private void migrate(String version) {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .target(version).load().migrate();
    }

    private void insertAttachment(long id) {
        jdbc.update("""
                INSERT INTO trip_attachments (trip_attachment_id, trip_id, file_id,
                    region_origin, issue, analyze_storage_key, preview_storage_key)
                VALUES (?, 9001, 9001, 'UNKNOWN', 'NONE', 'analyze/photo.jpg', 'preview/photo.webp')
                """, id);
    }
}

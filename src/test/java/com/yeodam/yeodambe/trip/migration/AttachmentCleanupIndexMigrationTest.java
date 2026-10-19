package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.SharedTestMysql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentCleanupIndexMigrationTest {
    @Test
    void 기존_자료와_정리대상_조회결과를_유지하고_삭제시각_ID_인덱스를_추가한다() throws Exception {
        var database = SharedTestMysql.createDatabase();
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .target("24").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        jdbc.update("""
                INSERT INTO trips (trip_id, user_id, trip_name, start_date, end_date)
                VALUES (89001, 1, '정리인덱스', '2026-09-01', '2026-09-01')
                """);
        for (long id = 89001; id <= 89004; id++) {
            jdbc.update("""
                    INSERT INTO files (file_id, user_id, original_file_name, object_key, mime_type)
                    VALUES (?, 1, 'photo.jpg', ?, 'image/jpeg')
                    """, id, "original/" + id);
            jdbc.update("""
                    INSERT INTO trip_attachments (trip_attachment_id, trip_id, file_id,
                        region_origin, issue, analyze_storage_key, preview_storage_key)
                    VALUES (?, 89001, ?, 'UNKNOWN', 'NONE', ?, ?)
                    """, id, id, "analyze/" + id, "preview/" + id);
        }
        jdbc.update("UPDATE trip_attachments SET deleted_at = '2026-09-01' WHERE trip_attachment_id IN (89001, 89004)");
        jdbc.update("UPDATE trip_attachments SET deleted_at = '2026-10-01' WHERE trip_attachment_id = 89002");
        jdbc.update("UPDATE trip_attachments SET classification_status = 'DELETED' WHERE trip_attachment_id = 89004");
        String query = """
                SELECT a.trip_attachment_id FROM trip_attachments a
                JOIN files f ON f.file_id = a.file_id
                WHERE a.deleted_at IS NOT NULL AND a.deleted_at <= '2026-09-15'
                  AND a.classification_status <> 'DELETED'
                ORDER BY a.trip_attachment_id LIMIT 100
                """;
        var before = jdbc.queryForList(query, Long.class);
        assertThat(before).containsExactly(89001L);

        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .target("25").load().migrate();

        assertThat(jdbc.queryForList(query, Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_attachments", Long.class)).isEqualTo(4L);
        assertThat(jdbc.queryForList("SELECT object_key FROM files ORDER BY file_id", String.class))
                .containsExactly("original/89001", "original/89002", "original/89003", "original/89004");
        assertThat(jdbc.queryForObject("""
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'trip_attachments'
                  AND index_name = 'idx_cleanup_deleted_id'
                """, String.class)).isEqualTo("deleted_at,trip_attachment_id");
    }
}

package com.yeodam.yeodambe.trip.fixture;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

public class TripAnalysisTestFixture {
    private final JdbcTemplate jdbc;

    public TripAnalysisTestFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long createOwner() {
        String email = UUID.randomUUID() + "@yeodam.test";
        jdbc.update("INSERT INTO users (email, nickname) VALUES (?, '분석회원')", email);
        Long userId = jdbc.queryForObject("SELECT user_id FROM users WHERE email = ?", Long.class, email);
        jdbc.update("INSERT INTO user_stats (user_id) VALUES (?)", userId);
        return userId;
    }

    public Long createTrip(Long ownerId) {
        String name = "분석여행";
        jdbc.update("""
                INSERT INTO trips (user_id, trip_name, start_date, end_date, processing_status)
                VALUES (?, ?, '2026-09-01', '2026-09-02', 'COMPLETED')
                """, ownerId, name);
        Long tripId = jdbc.queryForObject("SELECT trip_id FROM trips WHERE user_id = ?", Long.class, ownerId);
        jdbc.update("""
                INSERT INTO trip_regions (trip_id, region_code, region_name, latitude, longitude)
                VALUES (?, '11000', '서울', 37.5665, 126.9780)
                """, tripId);
        return tripId;
    }

    public void markLegacyUpload(String uploadId) {
        jdbc.update("""
                UPDATE additional_attachment_executions execution
                JOIN additional_attachment_upload_batches batch ON batch.addition_id = execution.execution_id
                SET execution.processing_mode = 'LEGACY', execution.accepted_actor_id = NULL
                WHERE batch.upload_id = ?
                """, uploadId);
    }

    public Long createPhoto(Long tripId, Long ownerId) {
        String key = "fixture/" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO files (user_id, original_file_name, object_key, mime_type, upload_status,
                                   original_size_bytes)
                VALUES (?, 'photo.jpg', ?, 'image/jpeg', 'READY', 12)
                """, ownerId, key);
        Long fileId = jdbc.queryForObject("SELECT file_id FROM files WHERE object_key = ?", Long.class, key);
        jdbc.update("""
                INSERT INTO trip_attachments (trip_id, file_id, region_origin, issue,
                    analyze_storage_key, preview_storage_key, analyze_size_bytes, preview_size_bytes)
                VALUES (?, ?, 'UNKNOWN', 'NONE', ?, ?, 4, 4)
                """, tripId, fileId, key + "/analyze", key + "/preview");
        return jdbc.queryForObject("SELECT trip_attachment_id FROM trip_attachments WHERE file_id = ?",
                Long.class, fileId);
    }

    public Addition prepareAddition(Long tripId, Long ownerId) {
        String additionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO additional_attachment_executions
                    (execution_id, trip_id, user_id, accepted_actor_id, processing_mode,
                     total_attachment_count, input_closed, status, accounted_bytes)
                VALUES (?, ?, ?, ?, 'V3', 1, TRUE, 'IMAGE_PROCESSING', 12)
                """, additionId, tripId, ownerId, ownerId);
        String uploadId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO additional_attachment_upload_batches
                    (upload_id, addition_id, trip_id, user_id, batch_no, total_attachment_count, is_last_batch, status)
                VALUES (?, ?, ?, ?, 1, 1, TRUE, 'PREPARED')
                """, uploadId, additionId, tripId, ownerId);
        Long batchId = jdbc.queryForObject("""
                SELECT upload_batch_id FROM additional_attachment_upload_batches WHERE upload_id = ?
                """, Long.class, uploadId);
        Long photoId = createPhoto(tripId, ownerId);
        String key = jdbc.queryForObject("""
                SELECT file.object_key FROM files file
                JOIN trip_attachments photo ON photo.file_id = file.file_id WHERE photo.trip_attachment_id = ?
                """, String.class, photoId);
        jdbc.update("""
                INSERT INTO additional_attachment_upload_items
                    (upload_batch_id, file_order, original_file_name, content_type, size_bytes,
                     object_key, trip_attachment_id)
                VALUES (?, 1, 'photo.jpg', 'image/jpeg', 12, ?, ?)
                """, batchId, key, photoId);
        return new Addition(additionId, batchId, photoId);
    }

    public void completeAnalysis(String executionId) {
        jdbc.update("""
                UPDATE additional_attachment_upload_batches batch
                JOIN trip_analysis_execution_uploads included ON included.addition_id = batch.addition_id
                SET batch.status = 'COMPLETED' WHERE included.execution_id = ?
                """, executionId);
        jdbc.update("""
                UPDATE additional_attachment_executions addition
                JOIN trip_analysis_execution_uploads included ON included.addition_id = addition.execution_id
                SET addition.status = 'COMPLETED', addition.ended_at = CURRENT_TIMESTAMP(6)
                WHERE included.execution_id = ?
                """, executionId);
        jdbc.update("""
                UPDATE trip_analysis_executions SET status = 'COMPLETED', ended_at = CURRENT_TIMESTAMP(6)
                WHERE execution_id = ?
                """, executionId);
    }

    public record Addition(
            String additionId,
            Long batchId,
            Long photoId
    ) {
    }
}

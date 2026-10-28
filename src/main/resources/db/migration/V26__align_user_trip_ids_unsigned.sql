DROP TEMPORARY TABLE IF EXISTS unsigned_id_preflight;
CREATE TEMPORARY TABLE unsigned_id_preflight (
    negative_count BIGINT NOT NULL,
    CONSTRAINT ck_unsigned_id_preflight CHECK (negative_count = 0)
);

INSERT INTO unsigned_id_preflight (negative_count)
SELECT COUNT(*) FROM users WHERE user_id < 0
UNION ALL
SELECT COUNT(*) FROM oauth_accounts WHERE user_id < 0
UNION ALL
SELECT COUNT(*) FROM user_stats WHERE user_id < 0
UNION ALL
SELECT COUNT(*) FROM consents WHERE user_id < 0
UNION ALL
SELECT COUNT(*) FROM trips WHERE trip_id < 0 OR user_id < 0
UNION ALL
SELECT COUNT(*) FROM trip_regions WHERE trip_id < 0
UNION ALL
SELECT COUNT(*) FROM trip_detail_places WHERE trip_id < 0
UNION ALL
SELECT COUNT(*) FROM files WHERE user_id < 0
UNION ALL
SELECT COUNT(*) FROM trip_attachments WHERE trip_id < 0
UNION ALL
SELECT COUNT(*) FROM login_sessions WHERE user_id < 0
UNION ALL
SELECT COUNT(*) FROM initial_attachment_upload_batches WHERE trip_id < 0 OR user_id < 0
UNION ALL
SELECT COUNT(*) FROM stories WHERE trip_id < 0
UNION ALL
SELECT COUNT(*) FROM story_generation_jobs WHERE trip_id < 0 OR user_id < 0
UNION ALL
SELECT COUNT(*) FROM trip_creation_executions WHERE trip_id < 0 OR user_id < 0
UNION ALL
SELECT COUNT(*) FROM additional_attachment_upload_batches WHERE trip_id < 0 OR user_id < 0
UNION ALL
SELECT COUNT(*) FROM additional_attachment_executions WHERE trip_id < 0 OR user_id < 0
UNION ALL
SELECT COUNT(*) FROM trip_creation_outbox WHERE trip_id < 0
UNION ALL
SELECT COUNT(*) FROM story_generation_outbox WHERE trip_id < 0
UNION ALL
SELECT COUNT(*) FROM additional_attachment_upload_outbox WHERE trip_id < 0;
DROP TEMPORARY TABLE unsigned_id_preflight;

ALTER TABLE oauth_accounts DROP FOREIGN KEY fk_oauth_accounts_user;
ALTER TABLE user_stats DROP FOREIGN KEY fk_user_stats_user;
ALTER TABLE consents DROP FOREIGN KEY fk_consents_user;
ALTER TABLE trips DROP FOREIGN KEY fk_trips_user;
ALTER TABLE trip_regions DROP FOREIGN KEY fk_trip_regions_trip;
ALTER TABLE trip_detail_places DROP FOREIGN KEY fk_trip_detail_places_trip;
ALTER TABLE files DROP FOREIGN KEY fk_files_user;
ALTER TABLE trip_attachments DROP FOREIGN KEY fk_trip_attachments_trip;
ALTER TABLE login_sessions DROP FOREIGN KEY fk_login_sessions_user;
ALTER TABLE initial_attachment_upload_batches DROP FOREIGN KEY fk_initial_upload_batches_trip;
ALTER TABLE initial_attachment_upload_batches DROP FOREIGN KEY fk_initial_upload_batches_user;
ALTER TABLE stories DROP FOREIGN KEY fk_stories_trip;
ALTER TABLE story_generation_jobs DROP FOREIGN KEY fk_story_generation_trip;
ALTER TABLE story_generation_jobs DROP FOREIGN KEY fk_story_generation_user;
ALTER TABLE trip_creation_executions DROP FOREIGN KEY fk_trip_creation_execution_trip;
ALTER TABLE trip_creation_executions DROP FOREIGN KEY fk_trip_creation_execution_user;
ALTER TABLE additional_attachment_upload_batches DROP FOREIGN KEY fk_additional_upload_batches_trip;
ALTER TABLE additional_attachment_upload_batches DROP FOREIGN KEY fk_additional_upload_batches_user;
ALTER TABLE additional_attachment_executions DROP FOREIGN KEY fk_additional_execution_trip;
ALTER TABLE additional_attachment_executions DROP FOREIGN KEY fk_additional_execution_user;

ALTER TABLE users MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT;
ALTER TABLE oauth_accounts MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE user_stats MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE consents MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE trips MODIFY COLUMN trip_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE trip_regions MODIFY COLUMN trip_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE trip_detail_places MODIFY COLUMN trip_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE files MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE trip_attachments MODIFY COLUMN trip_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE login_sessions MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE initial_attachment_upload_batches MODIFY COLUMN trip_id BIGINT UNSIGNED NOT NULL, MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE stories MODIFY COLUMN trip_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE story_generation_jobs MODIFY COLUMN trip_id BIGINT UNSIGNED NOT NULL, MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE trip_creation_executions MODIFY COLUMN trip_id BIGINT UNSIGNED NOT NULL, MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE additional_attachment_upload_batches MODIFY COLUMN trip_id BIGINT UNSIGNED NOT NULL, MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE additional_attachment_executions MODIFY COLUMN trip_id BIGINT UNSIGNED NOT NULL, MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE trip_creation_outbox MODIFY COLUMN trip_id BIGINT UNSIGNED NOT NULL;
ALTER TABLE story_generation_outbox MODIFY COLUMN trip_id BIGINT UNSIGNED NULL;
ALTER TABLE additional_attachment_upload_outbox MODIFY COLUMN trip_id BIGINT UNSIGNED NULL;

ALTER TABLE oauth_accounts ADD CONSTRAINT fk_oauth_accounts_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE user_stats ADD CONSTRAINT fk_user_stats_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE consents ADD CONSTRAINT fk_consents_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE trips ADD CONSTRAINT fk_trips_user FOREIGN KEY (user_id) REFERENCES users (user_id);
ALTER TABLE trip_regions ADD CONSTRAINT fk_trip_regions_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id);
ALTER TABLE trip_detail_places ADD CONSTRAINT fk_trip_detail_places_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id);
ALTER TABLE files ADD CONSTRAINT fk_files_user FOREIGN KEY (user_id) REFERENCES users (user_id);
ALTER TABLE trip_attachments ADD CONSTRAINT fk_trip_attachments_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id);
ALTER TABLE login_sessions ADD CONSTRAINT fk_login_sessions_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE initial_attachment_upload_batches ADD CONSTRAINT fk_initial_upload_batches_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id);
ALTER TABLE initial_attachment_upload_batches ADD CONSTRAINT fk_initial_upload_batches_user FOREIGN KEY (user_id) REFERENCES users (user_id);
ALTER TABLE stories ADD CONSTRAINT fk_stories_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id);
ALTER TABLE story_generation_jobs ADD CONSTRAINT fk_story_generation_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id);
ALTER TABLE story_generation_jobs ADD CONSTRAINT fk_story_generation_user FOREIGN KEY (user_id) REFERENCES users (user_id);
ALTER TABLE trip_creation_executions ADD CONSTRAINT fk_trip_creation_execution_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id);
ALTER TABLE trip_creation_executions ADD CONSTRAINT fk_trip_creation_execution_user FOREIGN KEY (user_id) REFERENCES users (user_id);
ALTER TABLE additional_attachment_upload_batches ADD CONSTRAINT fk_additional_upload_batches_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id);
ALTER TABLE additional_attachment_upload_batches ADD CONSTRAINT fk_additional_upload_batches_user FOREIGN KEY (user_id) REFERENCES users (user_id);
ALTER TABLE additional_attachment_executions ADD CONSTRAINT fk_additional_execution_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id);
ALTER TABLE additional_attachment_executions ADD CONSTRAINT fk_additional_execution_user FOREIGN KEY (user_id) REFERENCES users (user_id);

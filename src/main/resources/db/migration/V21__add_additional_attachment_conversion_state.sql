ALTER TABLE additional_attachment_upload_batches
    ADD COLUMN next_attempt_at DATETIME(6) NULL,
    DROP INDEX idx_additional_upload_batches_work,
    ADD INDEX idx_additional_upload_batches_work
        (status, next_attempt_at, lease_expires_at, upload_batch_id);

ALTER TABLE additional_attachment_upload_items
    ADD COLUMN conversion_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN conversion_started_at DATETIME(6) NULL,
    ADD COLUMN conversion_next_attempt_at DATETIME(6) NULL,
    ADD COLUMN derived_json LONGTEXT NULL,
    ADD COLUMN cleanup_json LONGTEXT NULL,
    ADD COLUMN cleanup_started_at DATETIME(6) NULL,
    ADD INDEX idx_additional_upload_items_cleanup
        (cleanup_started_at);

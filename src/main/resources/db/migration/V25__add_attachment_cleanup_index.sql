CREATE INDEX idx_cleanup_deleted_id
    ON trip_attachments (deleted_at, trip_attachment_id);

ALTER TABLE files
    ADD COLUMN original_size_bytes BIGINT NULL;

ALTER TABLE trip_attachments
    ADD COLUMN analyze_size_bytes BIGINT NULL,
    ADD COLUMN preview_size_bytes BIGINT NULL,
    ADD COLUMN display_size_bytes BIGINT NULL;
ALTER TABLE trip_attachments
    ADD COLUMN display_storage_key VARCHAR(500) NULL
    AFTER preview_storage_key;

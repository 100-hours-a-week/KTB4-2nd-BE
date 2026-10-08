ALTER TABLE trip_attachments
    ADD CONSTRAINT uk_trip_attachments_file UNIQUE (file_id);
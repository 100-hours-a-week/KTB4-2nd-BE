ALTER TABLE additional_attachment_upload_outbox
    ADD COLUMN claim_token VARCHAR(36) NULL,
    ADD COLUMN lease_until DATETIME(6) NULL,
    ADD COLUMN next_attempt_at DATETIME(6) NULL;
CREATE INDEX idx_additional_outbox_delivery ON additional_attachment_upload_outbox(status, next_attempt_at, lease_until, outbox_id);

CREATE TABLE additional_attachment_upload_outbox (
    outbox_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    upload_batch_id BIGINT NOT NULL,
    message_type VARCHAR(20) NOT NULL,
    payload LONGTEXT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    published_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_additional_outbox_batch_type
        UNIQUE (upload_batch_id, message_type),

    CONSTRAINT fk_additional_outbox_batch
        FOREIGN KEY (upload_batch_id)
            REFERENCES additional_attachment_upload_batches (upload_batch_id),

    CONSTRAINT ck_additional_outbox_message_type
        CHECK (message_type IN ('photos_ready', 'process')),

    CONSTRAINT ck_additional_outbox_status
        CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED'))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

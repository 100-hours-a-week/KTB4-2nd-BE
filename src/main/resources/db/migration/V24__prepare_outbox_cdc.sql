ALTER TABLE story_generation_outbox
    ADD COLUMN trip_id BIGINT,
    ADD COLUMN topic VARCHAR(255),
    ADD COLUMN event_source VARCHAR(64) NOT NULL DEFAULT 'story_generation_outbox',
    ADD COLUMN cdc_managed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE additional_attachment_upload_outbox
    ADD COLUMN trip_id BIGINT,
    ADD COLUMN topic VARCHAR(255),
    ADD COLUMN event_source VARCHAR(64) NOT NULL DEFAULT 'additional_attachment_upload_outbox',
    ADD COLUMN cdc_managed BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE story_generation_outbox outbox
    JOIN story_generation_jobs job ON job.generation_id = outbox.generation_id
    SET outbox.trip_id = job.trip_id;

UPDATE additional_attachment_upload_outbox outbox
    JOIN additional_attachment_upload_batches batch ON batch.upload_batch_id = outbox.upload_batch_id
    SET outbox.trip_id = batch.trip_id;

CREATE TABLE trip_creation_outbox (
    outbox_id VARCHAR(36) NOT NULL PRIMARY KEY,
    execution_id VARCHAR(36) NOT NULL,
    message_type VARCHAR(20) NOT NULL,
    batch_no INT NOT NULL,
    trip_id BIGINT NOT NULL,
    topic VARCHAR(255) NOT NULL,
    event_source VARCHAR(64) NOT NULL DEFAULT 'trip_creation_outbox',
    cdc_managed BOOLEAN NOT NULL DEFAULT TRUE,
    payload LONGTEXT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    published_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_trip_creation_outbox_message UNIQUE (execution_id, message_type, batch_no),
    CONSTRAINT fk_trip_creation_outbox_execution FOREIGN KEY (execution_id)
        REFERENCES trip_creation_executions (execution_id),
    CONSTRAINT ck_trip_creation_outbox_type CHECK (message_type IN ('photos_ready', 'process')),
    CONSTRAINT ck_trip_creation_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

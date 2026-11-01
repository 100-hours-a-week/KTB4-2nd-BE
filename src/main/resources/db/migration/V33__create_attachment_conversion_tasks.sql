ALTER TABLE initial_attachment_upload_batches
    ADD COLUMN conversion_route VARCHAR(20) NOT NULL DEFAULT 'DB',
    ADD CONSTRAINT ck_initial_conversion_route CHECK (conversion_route IN ('DB', 'HOLD', 'KAFKA'));

ALTER TABLE additional_attachment_upload_batches
    ADD COLUMN conversion_route VARCHAR(20) NOT NULL DEFAULT 'DB',
    ADD CONSTRAINT ck_additional_conversion_route CHECK (conversion_route IN ('DB', 'HOLD', 'KAFKA'));

CREATE TABLE attachment_conversion_tasks (
    task_id VARCHAR(36) NOT NULL PRIMARY KEY,
    initial_batch_id BIGINT,
    additional_batch_id BIGINT,
    trip_id BIGINT UNSIGNED NOT NULL,
    kind VARCHAR(20) NOT NULL,
    lane VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    total_photos BIGINT NOT NULL,
    remaining_photos BIGINT NOT NULL,
    total_bytes BIGINT NOT NULL,
    remaining_bytes BIGINT NOT NULL,
    claim_token VARCHAR(36),
    lease_until DATETIME(6),
    next_attempt_at DATETIME(6),
    current_item_id BIGINT,
    current_attempt_no INT,
    current_attempt_token VARCHAR(36),
    latest_delivery_no BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_conversion_initial_lane UNIQUE (initial_batch_id, lane),
    CONSTRAINT uk_conversion_additional_lane UNIQUE (additional_batch_id, lane),
    CONSTRAINT ck_conversion_source_batch CHECK (
        (kind = 'INITIAL' AND initial_batch_id IS NOT NULL AND additional_batch_id IS NULL)
        OR (kind = 'ADDITIONAL' AND initial_batch_id IS NULL AND additional_batch_id IS NOT NULL)
    ),
    CONSTRAINT ck_conversion_lane CHECK (lane IN ('LIGHT', 'HEIC')),
    CONSTRAINT ck_conversion_status CHECK (status IN ('WAITING', 'RUNNING', 'SUCCEEDED', 'CANCELED', 'FAILED')),
    CONSTRAINT fk_conversion_initial_batch FOREIGN KEY (initial_batch_id)
        REFERENCES initial_attachment_upload_batches (upload_batch_id),
    CONSTRAINT fk_conversion_additional_batch FOREIGN KEY (additional_batch_id)
        REFERENCES additional_attachment_upload_batches (upload_batch_id),
    CONSTRAINT fk_conversion_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id),
    INDEX idx_conversion_recovery (status, created_at, task_id),
    INDEX idx_conversion_lane_status (lane, status)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE attachment_conversion_outbox (
    outbox_id VARCHAR(36) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    delivery_no BIGINT NOT NULL,
    trip_id BIGINT UNSIGNED NOT NULL,
    topic VARCHAR(255) NOT NULL,
    payload LONGTEXT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    event_source VARCHAR(255) NOT NULL DEFAULT 'attachment_conversion_outbox',
    cdc_managed BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    topic_id VARCHAR(255),
    record_partition INT,
    record_offset BIGINT,
    broker_timestamp TIMESTAMP(6) NULL,
    leader_epoch INT,
    publication_version BIGINT NOT NULL DEFAULT 0,
    published_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_conversion_outbox_delivery UNIQUE (task_id, delivery_no),
    CONSTRAINT ck_conversion_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT fk_conversion_outbox_task FOREIGN KEY (task_id) REFERENCES attachment_conversion_tasks (task_id),
    CONSTRAINT fk_conversion_outbox_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

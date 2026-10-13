CREATE TABLE additional_attachment_executions (
    execution_id VARCHAR(36) NOT NULL PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'UPLOADING',

    total_attachment_count INT NOT NULL,
    analysis_attachment_count INT,
    input_closed BOOLEAN NOT NULL DEFAULT FALSE,
    last_upload_activity_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6),

    reserved_bytes BIGINT NOT NULL DEFAULT 0,
    accounted_bytes BIGINT NOT NULL DEFAULT 0,
    reservation_released BOOLEAN NOT NULL DEFAULT FALSE,

    process_outbox_id BIGINT,
    process_published_at DATETIME(6),

    result_raw_payload LONGTEXT,
    result_ai_status VARCHAR(20),
    result_received_at DATETIME(6),
    result_conflicts LONGTEXT,

    result_attempts INT NOT NULL DEFAULT 0,
    result_attempt_started_at DATETIME(6),
    result_failed_at DATETIME(6),
    result_failure_reason VARCHAR(255),
    result_claim_token VARCHAR(36),
    result_lease_until DATETIME(6),
    result_next_attempt_at DATETIME(6),

    ended_at DATETIME(6),
    error_code VARCHAR(100),

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_additional_execution_trip
        FOREIGN KEY (trip_id) REFERENCES trips (trip_id),

    CONSTRAINT fk_additional_execution_user
        FOREIGN KEY (user_id) REFERENCES users (user_id),

    CONSTRAINT fk_additional_execution_process_outbox
        FOREIGN KEY (process_outbox_id)
            REFERENCES additional_attachment_upload_outbox (outbox_id),

    CONSTRAINT ck_additional_execution_bytes
        CHECK (reserved_bytes >= 0 AND accounted_bytes >= 0),

    CONSTRAINT ck_additional_execution_counts
        CHECK (
            total_attachment_count BETWEEN 1 AND 200
            AND (
                analysis_attachment_count IS NULL
                OR analysis_attachment_count BETWEEN 1 AND 200
            )
        ),

    INDEX idx_additional_execution_trip
        (trip_id, created_at),

    INDEX idx_additional_execution_upload_timeout
        (ended_at, input_closed, last_upload_activity_at),

    INDEX idx_additional_execution_result_timeout
        (ended_at, process_published_at),

    INDEX idx_additional_execution_result_claim
        (status, result_next_attempt_at, result_lease_until)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

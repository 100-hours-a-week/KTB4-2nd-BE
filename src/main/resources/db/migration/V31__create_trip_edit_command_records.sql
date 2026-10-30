CREATE TABLE trip_edit_command_records (
    command_record_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    trip_id BIGINT UNSIGNED NOT NULL,
    request_id VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    command_type VARCHAR(30) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    result_json LONGTEXT NULL,
    completed_at DATETIME(6) NOT NULL,
    result_expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (command_record_id),
    CONSTRAINT uk_trip_edit_command_user_request UNIQUE (user_id, request_id),
    INDEX idx_trip_edit_command_expiry (result_expires_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

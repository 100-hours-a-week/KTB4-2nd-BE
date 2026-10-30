CREATE TABLE trip_edit_notification_outbox (
    notification_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    target_type VARCHAR(10) NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    event_json LONGTEXT NOT NULL,
    status VARCHAR(10) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (notification_id),
    INDEX idx_trip_edit_notification_due (status, next_attempt_at, notification_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

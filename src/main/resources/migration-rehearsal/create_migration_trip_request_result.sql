-- Apply only to the promoted rehearsal Target before enabling MIGRATION_TRIP_CONSUMER_ENABLED.
-- This is deliberately outside Flyway: the deployed V1 image contains V1-V8 while the
-- long-lived rehearsal DB records later migration versions from a different BE line.
CREATE TABLE IF NOT EXISTS migration_trip_request_result (
    request_id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    trip_id BIGINT NULL,
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_migration_trip_result_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE trip_creation_executions
    ADD COLUMN status_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN display_status VARCHAR(24) NOT NULL DEFAULT 'PROCESSING',
    ADD COLUMN current_step TEXT NULL;

ALTER TABLE additional_attachment_executions
    ADD COLUMN status_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN display_status VARCHAR(24) NOT NULL DEFAULT 'PROCESSING',
    ADD COLUMN current_step TEXT NULL;

ALTER TABLE story_generation_jobs
    ADD COLUMN status_version BIGINT NOT NULL DEFAULT 0;

UPDATE trip_creation_executions
SET display_status = CASE status
    WHEN 'RESULT_APPLYING' THEN 'FINALIZING'
    WHEN 'COMPLETED' THEN 'COMPLETED'
    WHEN 'FAILED' THEN 'FAILED'
    WHEN 'CANCELED' THEN 'CANCELED'
    ELSE 'PROCESSING'
END;

UPDATE additional_attachment_executions
SET display_status = CASE status
    WHEN 'RESULT_APPLYING' THEN 'FINALIZING'
    WHEN 'COMPLETED' THEN 'COMPLETED'
    WHEN 'FAILED' THEN 'FAILED'
    WHEN 'CANCELED' THEN 'CANCELED'
    ELSE 'PROCESSING'
END;

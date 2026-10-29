ALTER TABLE trip_creation_executions
    ALTER COLUMN status_version SET DEFAULT 1;

ALTER TABLE additional_attachment_executions
    ALTER COLUMN status_version SET DEFAULT 1;

ALTER TABLE story_generation_jobs
    ALTER COLUMN status_version SET DEFAULT 1;

UPDATE trip_creation_executions
SET status_version = status_version + 1;

UPDATE additional_attachment_executions
SET status_version = status_version + 1;

UPDATE story_generation_jobs
SET status_version = status_version + 1;

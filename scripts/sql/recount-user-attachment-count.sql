-- Set @attachment_count_target_user_id in this session before running this script.
-- Run once per user after all writers use the new count policy; safe to repeat.
-- On any statement failure, ROLLBACK and stop instead of continuing this script.
SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;

SET @attachment_count_stats_id = NULL;
SELECT user_stats_id INTO @attachment_count_stats_id
FROM user_stats
WHERE user_id = @attachment_count_target_user_id
  AND deleted_at IS NULL
FOR UPDATE;

-- This consistent read takes no trip locks after the stats lock.
SELECT CASE WHEN EXISTS (
    SELECT 1 FROM users
    WHERE user_id = @attachment_count_target_user_id AND deleted_at IS NULL
) THEN COUNT(attachment.trip_attachment_id) ELSE NULL END
INTO @attachment_count_value
FROM trip_attachments attachment
JOIN trips trip ON trip.trip_id = attachment.trip_id
JOIN files file ON file.file_id = attachment.file_id
WHERE trip.user_id = @attachment_count_target_user_id
  AND trip.processing_status = 'COMPLETED'
  AND trip.deleted_at IS NULL
  AND attachment.deleted_at IS NULL
  AND file.deleted_at IS NULL
  AND attachment.classification_status IN ('ACTIVE', 'UNCLASSIFIED')
  AND NOT EXISTS (
      SELECT 1
      FROM additional_attachment_upload_items item
      JOIN additional_attachment_upload_batches batch ON batch.upload_batch_id = item.upload_batch_id
      WHERE item.trip_attachment_id = attachment.trip_attachment_id
        AND batch.status <> 'COMPLETED'
  );

UPDATE user_stats
SET attachment_count = @attachment_count_value
WHERE user_stats_id = @attachment_count_stats_id
  AND deleted_at IS NULL
  AND @attachment_count_value IS NOT NULL;

COMMIT;

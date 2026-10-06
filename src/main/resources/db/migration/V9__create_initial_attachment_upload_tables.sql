CREATE TABLE initial_attachment_upload_batches (
                                                   upload_batch_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                                   upload_id VARCHAR(36) NOT NULL,
                                                   execution_id VARCHAR(36) NOT NULL,
                                                   trip_id BIGINT NOT NULL,
                                                   user_id BIGINT NOT NULL,
                                                   batch_no INT NOT NULL,
                                                   total_attachment_count INT NOT NULL,
                                                   is_last_batch BOOLEAN NOT NULL,
                                                   status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                                   created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                                   updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

                                                   CONSTRAINT uk_initial_upload_batches_upload_id
                                                       UNIQUE (upload_id),
                                                   CONSTRAINT uk_initial_upload_batches_execution_batch
                                                       UNIQUE (execution_id, batch_no),
                                                   INDEX idx_initial_upload_batches_trip (trip_id, upload_batch_id),

                                                   CONSTRAINT fk_initial_upload_batches_trip
                                                       FOREIGN KEY (trip_id) REFERENCES trips (trip_id),
                                                   CONSTRAINT fk_initial_upload_batches_user
                                                       FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE initial_attachment_upload_items (
                                                 upload_item_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                                 upload_batch_id BIGINT NOT NULL,
                                                 file_order INT NOT NULL,
                                                 original_file_name VARCHAR(255) NOT NULL,
                                                 content_type VARCHAR(100) NOT NULL,
                                                 size_bytes BIGINT NOT NULL,
                                                 object_key VARCHAR(500) NOT NULL,
                                                 trip_attachment_id BIGINT,
                                                 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                                 CONSTRAINT uk_initial_upload_items_order
                                                     UNIQUE (upload_batch_id, file_order),

                                                 CONSTRAINT fk_initial_upload_items_batch
                                                     FOREIGN KEY (upload_batch_id)
                                                         REFERENCES initial_attachment_upload_batches (upload_batch_id),
                                                 CONSTRAINT fk_initial_upload_items_attachment
                                                     FOREIGN KEY (trip_attachment_id)
                                                         REFERENCES trip_attachments (trip_attachment_id)
                                                         ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
CREATE TABLE additional_attachment_upload_batches (
                                                      upload_batch_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                                      upload_id VARCHAR(36) NOT NULL,
                                                      addition_id VARCHAR(36) NOT NULL,
                                                      trip_id BIGINT NOT NULL,
                                                      user_id BIGINT NOT NULL,
                                                      batch_no INT NOT NULL,
                                                      total_attachment_count INT NOT NULL,
                                                      is_last_batch BOOLEAN NOT NULL,
                                                      status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                                      worker_token VARCHAR(36) NULL,
                                                      lease_expires_at DATETIME(6) NULL,
                                                      created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                                      updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

                                                      CONSTRAINT uk_additional_upload_batches_upload_id
                                                          UNIQUE (upload_id),
                                                      CONSTRAINT uk_additional_upload_batches_addition_batch
                                                          UNIQUE (addition_id, batch_no),

                                                      INDEX idx_additional_upload_batches_trip
                                                          (trip_id, upload_batch_id),
                                                      INDEX idx_additional_upload_batches_work
                                                          (status, lease_expires_at, upload_batch_id),

                                                      CONSTRAINT fk_additional_upload_batches_trip
                                                          FOREIGN KEY (trip_id) REFERENCES trips (trip_id),
                                                      CONSTRAINT fk_additional_upload_batches_user
                                                          FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE additional_attachment_upload_items (
                                                    upload_item_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                                    upload_batch_id BIGINT NOT NULL,
                                                    file_order INT NOT NULL,
                                                    original_file_name VARCHAR(255) NOT NULL,
                                                    content_type VARCHAR(100) NOT NULL,
                                                    size_bytes BIGINT NOT NULL,
                                                    object_key VARCHAR(500) NOT NULL,
                                                    trip_attachment_id BIGINT NULL,
                                                    taken_at_with_offset VARCHAR(40) NULL,
                                                    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                                    CONSTRAINT uk_additional_upload_items_order
                                                        UNIQUE (upload_batch_id, file_order),

                                                    CONSTRAINT fk_additional_upload_items_batch
                                                        FOREIGN KEY (upload_batch_id)
                                                            REFERENCES additional_attachment_upload_batches (upload_batch_id),
                                                    CONSTRAINT fk_additional_upload_items_attachment
                                                        FOREIGN KEY (trip_attachment_id)
                                                            REFERENCES trip_attachments (trip_attachment_id)
                                                            ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
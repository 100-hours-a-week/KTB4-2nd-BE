CREATE TABLE story_generation_outbox (
                                         outbox_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                         generation_id BIGINT NOT NULL,
                                         payload LONGTEXT NOT NULL,
                                         request_id VARCHAR(128) NOT NULL,
                                         status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                         attempt_count INT NOT NULL DEFAULT 0,
                                         last_error TEXT,
                                         published_at DATETIME(6),
                                         created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                         updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

                                         CONSTRAINT uk_story_outbox_generation
                                             UNIQUE (generation_id),

                                         CONSTRAINT fk_story_outbox_generation
                                             FOREIGN KEY (generation_id)
                                                 REFERENCES story_generation_jobs (generation_id),

                                         CONSTRAINT ck_story_outbox_status
                                             CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED'))

) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

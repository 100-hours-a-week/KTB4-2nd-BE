CREATE TABLE story_generation_jobs (
                                       generation_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                       execution_id VARCHAR(36) NOT NULL,
                                       trip_id BIGINT NOT NULL,
                                       user_id BIGINT NOT NULL,
                                       mood VARCHAR(20) NOT NULL,
                                       status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
                                       progress INT NOT NULL DEFAULT 0,
                                       story_id BIGINT,
                                       error_code VARCHAR(100),
                                       active_trip_id BIGINT GENERATED ALWAYS AS (
                                           CASE
                                               WHEN status IN ('QUEUED', 'PROCESSING') THEN trip_id
                                               ELSE NULL
                                               END
                                           ) STORED,
                                       created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                       updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

                                       CONSTRAINT uk_story_generation_execution
                                           UNIQUE (execution_id),
                                       CONSTRAINT uk_story_generation_active_trip
                                           UNIQUE (active_trip_id),
                                       CONSTRAINT ck_story_generation_status
                                           CHECK (status IN (
                                                             'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELED'
                                               )),
                                       CONSTRAINT ck_story_generation_progress
                                           CHECK (progress BETWEEN 0 AND 100),
                                       CONSTRAINT fk_story_generation_trip
                                           FOREIGN KEY (trip_id) REFERENCES trips (trip_id),
                                       CONSTRAINT fk_story_generation_user
                                           FOREIGN KEY (user_id) REFERENCES users (user_id),
                                       CONSTRAINT fk_story_generation_story
                                           FOREIGN KEY (story_id) REFERENCES stories (story_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE story_generation_places (
                                         generation_place_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                         generation_id BIGINT NOT NULL,
                                         trip_place_id BIGINT NOT NULL,
                                         order_number INT NOT NULL,
                                         created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                         CONSTRAINT uk_story_generation_place
                                             UNIQUE (generation_id, trip_place_id),
                                         CONSTRAINT uk_story_generation_place_order
                                             UNIQUE (generation_id, order_number),
                                         CONSTRAINT fk_story_generation_place_job
                                             FOREIGN KEY (generation_id)
                                                 REFERENCES story_generation_jobs (generation_id),
                                         CONSTRAINT fk_story_generation_place_folder
                                             FOREIGN KEY (trip_place_id)
                                                 REFERENCES trip_detail_places (trip_place_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
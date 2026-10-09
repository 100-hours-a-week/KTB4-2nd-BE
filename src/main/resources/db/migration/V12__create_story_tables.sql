CREATE TABLE stories (
    story_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    mood VARCHAR(20) NOT NULL,
    story_summary VARCHAR(40) NOT NULL,
    processing_status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6),
    CONSTRAINT fk_stories_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE story_selected_places (
    story_place_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    story_id BIGINT NOT NULL,
    trip_place_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6),
    CONSTRAINT fk_story_selected_places_story FOREIGN KEY (story_id) REFERENCES stories (story_id),
    CONSTRAINT fk_story_selected_places_place FOREIGN KEY (trip_place_id) REFERENCES trip_detail_places (trip_place_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE story_blocks (
    story_block_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    story_id BIGINT NOT NULL,
    trip_attachment_id BIGINT NOT NULL,
    order_number INT NOT NULL,
    day_label VARCHAR(40) NOT NULL,
    detail_summary VARCHAR(40) NOT NULL,
    memo VARCHAR(125),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6),
    CONSTRAINT fk_story_blocks_story FOREIGN KEY (story_id) REFERENCES stories (story_id),
    CONSTRAINT fk_story_blocks_attachment FOREIGN KEY (trip_attachment_id) REFERENCES trip_attachments (trip_attachment_id),
    CONSTRAINT uq_story_blocks_attachment_story UNIQUE (trip_attachment_id, story_id),
    INDEX idx_story_blocks_read (story_id, deleted_at, order_number, story_block_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE trips
    ADD COLUMN current_story_id BIGINT NULL DEFAULT NULL,
    ADD CONSTRAINT fk_trips_current_story FOREIGN KEY (current_story_id) REFERENCES stories (story_id);

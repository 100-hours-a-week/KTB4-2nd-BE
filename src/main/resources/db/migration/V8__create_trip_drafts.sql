CREATE TABLE trip_drafts (
    draft_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    trip_name VARCHAR(10) NULL,
    region_codes VARCHAR(128) NOT NULL,
    start_date DATE NULL,
    end_date DATE NULL,
    submitted_trip_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_trip_drafts PRIMARY KEY (draft_id),
    CONSTRAINT uk_trip_drafts_user UNIQUE (user_id),
    CONSTRAINT fk_trip_drafts_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_trip_drafts_submitted_trip FOREIGN KEY (submitted_trip_id) REFERENCES trips (trip_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

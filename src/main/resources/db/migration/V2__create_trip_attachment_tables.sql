CREATE TABLE trips (
    trip_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    trip_name VARCHAR(10) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    is_favorite BOOLEAN NOT NULL DEFAULT FALSE,
    thumbnail_key VARCHAR(500),
    processing_status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6),
    CONSTRAINT fk_trips_user FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE trip_regions (
    region_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    region_code VARCHAR(5) NOT NULL,
    region_name VARCHAR(40) NOT NULL,
    latitude DECIMAL(10,8) NOT NULL,
    longitude DECIMAL(11,8) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6),
    CONSTRAINT fk_trip_regions_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE trip_detail_places (
    trip_place_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    place_name VARCHAR(50) NOT NULL,
    latitude DECIMAL(10,8) NOT NULL,
    longitude DECIMAL(11,8) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NOT NULL,
    order_number INT NOT NULL,
    thumbnail_key VARCHAR(500),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6),
    CONSTRAINT fk_trip_detail_places_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE files (
    file_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    upload_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    uploaded_at DATETIME(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6),
    CONSTRAINT fk_files_user FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE trip_attachments (
    trip_attachment_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    trip_place_id BIGINT,
    file_id BIGINT NOT NULL,
    region_origin VARCHAR(20) NOT NULL,
    issue VARCHAR(40) NOT NULL,
    classification_status VARCHAR(20) NOT NULL DEFAULT 'UNCLASSIFIED',
    analyze_storage_key VARCHAR(500) NOT NULL,
    preview_storage_key VARCHAR(500) NOT NULL,
    evaluation INT,
    taken_at DATETIME(6),
    latitude DECIMAL(10,8),
    longitude DECIMAL(11,8),
    device_model VARCHAR(100),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6),
    CONSTRAINT fk_trip_attachments_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id),
    CONSTRAINT fk_trip_attachments_file FOREIGN KEY (file_id) REFERENCES files (file_id),
    CONSTRAINT fk_trip_attachments_place FOREIGN KEY (trip_place_id) REFERENCES trip_detail_places (trip_place_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

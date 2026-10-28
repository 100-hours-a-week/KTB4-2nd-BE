CREATE TABLE trip_share_users (
    trip_share_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    trip_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    grantee_user_id BIGINT UNSIGNED NOT NULL,
    permission VARCHAR(10) NOT NULL DEFAULT 'READ',
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    CONSTRAINT pk_trip_share_users PRIMARY KEY (trip_share_id),
    CONSTRAINT fk_trip_share_trip FOREIGN KEY (trip_id) REFERENCES trips (trip_id),
    CONSTRAINT fk_trip_share_issuer FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_trip_share_grantee FOREIGN KEY (grantee_user_id) REFERENCES users (user_id),
    CONSTRAINT uk_trip_share_trip_grantee UNIQUE (trip_id, grantee_user_id),
    CONSTRAINT ck_trip_share_permission CHECK (permission IN ('READ', 'WRITE'))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

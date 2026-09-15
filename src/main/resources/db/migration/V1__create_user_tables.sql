CREATE TABLE users (
    user_id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_as_cs
        NOT NULL,
    nickname VARCHAR(10) NOT NULL COMMENT '중복을 허용하는 앱 내 닉네임',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL DEFAULT NULL,
    CONSTRAINT pk_users PRIMARY KEY (user_id),
    UNIQUE KEY uk_users_active_email (
        email,
        (IF(deleted_at IS NULL, 1, NULL))
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE oauth_accounts (
    oauth_account_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL DEFAULT NULL,
    CONSTRAINT pk_oauth_accounts PRIMARY KEY (oauth_account_id),
    CONSTRAINT fk_oauth_accounts_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT chk_oauth_accounts_provider
        CHECK (provider IN ('KAKAO')),
    UNIQUE KEY uk_oauth_accounts_active_provider_user (
        provider,
        provider_user_id,
        (IF(deleted_at IS NULL, 1, NULL))
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_stats (
    user_stats_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    trip_count BIGINT NOT NULL DEFAULT 0,
    story_count BIGINT NOT NULL DEFAULT 0,
    attachment_count BIGINT NOT NULL DEFAULT 0,
    storage_used_bytes BIGINT NOT NULL DEFAULT 0,
    storage_maximum_bytes BIGINT NOT NULL DEFAULT 30000000000,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL DEFAULT NULL,
    CONSTRAINT pk_user_stats PRIMARY KEY (user_stats_id),
    CONSTRAINT fk_user_stats_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    CONSTRAINT uk_user_stats_user UNIQUE (user_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE consents
(
    consent_id BIGINT  NOT NULL AUTO_INCREMENT,
    user_id    BIGINT  NOT NULL,
    is_agreed  BOOLEAN NOT NULL DEFAULT FALSE,
    agreed_at  DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL DEFAULT NULL,
    CONSTRAINT pk_consents PRIMARY KEY (consent_id),
    CONSTRAINT fk_consents_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT,
    CONSTRAINT uk_consents_user UNIQUE (user_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO users (user_id, email, nickname)
VALUES (1, 'system@yeodam.invalid', '시스템');

INSERT INTO user_stats (user_id)
VALUES (1);

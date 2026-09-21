CREATE TABLE oauth_states (
                              oauth_state_id BIGINT NOT NULL AUTO_INCREMENT,
                              state_hash CHAR(64)
                                                 CHARACTER SET ascii
                                  COLLATE ascii_bin
                                                    NOT NULL,
                              browser_context_hash CHAR(64)
                                                 CHARACTER SET ascii
                                  COLLATE ascii_bin
                                                    NOT NULL,
                              expires_at DATETIME(6) NOT NULL,
                              created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                              CONSTRAINT pk_oauth_states
                                  PRIMARY KEY (oauth_state_id),
                              CONSTRAINT uk_oauth_states_state_hash
                                  UNIQUE (state_hash),
                              INDEX idx_oauth_states_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE login_tickets (
                               login_ticket_id BIGINT NOT NULL AUTO_INCREMENT,
                               ticket_hash CHAR(64)
                                                   CHARACTER SET ascii
                                   COLLATE ascii_bin
                                                      NOT NULL,
                               browser_context_hash CHAR(64)
                                                   CHARACTER SET ascii
                                   COLLATE ascii_bin
                                                      NOT NULL,
                               provider_user_id VARCHAR(255) NOT NULL,
                               email VARCHAR(255)
                                                   CHARACTER SET utf8mb4
                                   COLLATE utf8mb4_0900_as_cs
                                   NOT NULL,
                               expires_at DATETIME(6) NOT NULL,
                               created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                               CONSTRAINT pk_login_tickets
                                   PRIMARY KEY (login_ticket_id),
                               CONSTRAINT uk_login_tickets_ticket_hash
                                   UNIQUE (ticket_hash),
                               INDEX idx_login_tickets_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE profile_tokens (
                                profile_token_id BIGINT NOT NULL AUTO_INCREMENT,
                                token_hash CHAR(64)
                                                     CHARACTER SET ascii
                                    COLLATE ascii_bin
                                                        NOT NULL,
                                provider_user_id VARCHAR(255) NOT NULL,
                                email VARCHAR(255)
                                                     CHARACTER SET utf8mb4
                                    COLLATE utf8mb4_0900_as_cs
                                    NOT NULL,
                                expires_at DATETIME(6) NOT NULL,
                                created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                CONSTRAINT pk_profile_tokens
                                    PRIMARY KEY (profile_token_id),
                                CONSTRAINT uk_profile_tokens_token_hash
                                    UNIQUE (token_hash),
                                INDEX idx_profile_tokens_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE csrf_tokens (
                             csrf_token_id BIGINT NOT NULL AUTO_INCREMENT,
                             browser_context_hash CHAR(64)
                                               CHARACTER SET ascii
                                 COLLATE ascii_bin
                                                  NOT NULL,
                             token_value VARCHAR(64)
                                               CHARACTER SET ascii
                                 COLLATE ascii_bin
                                                  NOT NULL,
                             expires_at DATETIME(6) NOT NULL,
                             created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                             updated_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
                             CONSTRAINT pk_csrf_tokens
                                 PRIMARY KEY (csrf_token_id),
                             CONSTRAINT uk_csrf_tokens_browser_context_hash
                                 UNIQUE (browser_context_hash),
                             INDEX idx_csrf_tokens_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE login_sessions (
                                login_session_id BIGINT NOT NULL AUTO_INCREMENT,
                                sid CHAR(36)
                                                     CHARACTER SET ascii
                                    COLLATE ascii_bin
                                                        NOT NULL,
                                user_id BIGINT NOT NULL,
                                refresh_token_hash CHAR(64)
                                                     CHARACTER SET ascii
                                    COLLATE ascii_bin
                                                        NOT NULL,
                                expires_at DATETIME(6) NOT NULL,
                                created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                updated_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
                                CONSTRAINT pk_login_sessions
                                    PRIMARY KEY (login_session_id),
                                CONSTRAINT fk_login_sessions_user
                                    FOREIGN KEY (user_id) REFERENCES users (user_id)
                                        ON UPDATE RESTRICT
                                        ON DELETE RESTRICT,
                                CONSTRAINT uk_login_sessions_sid
                                    UNIQUE (sid),
                                CONSTRAINT uk_login_sessions_refresh_token_hash
                                    UNIQUE (refresh_token_hash),
                                INDEX idx_login_sessions_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
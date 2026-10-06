CREATE TABLE deploy_drain_control (
                                      control_id BIGINT NOT NULL,
                                      draining BOOLEAN NOT NULL DEFAULT FALSE,
                                      updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

                                      CONSTRAINT pk_deploy_drain_control PRIMARY KEY (control_id),
                                      CONSTRAINT chk_deploy_drain_control_singleton
                                          CHECK (control_id = 1)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO deploy_drain_control (control_id, draining)
VALUES (1, FALSE);
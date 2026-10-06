package com.yeodam.yeodambe.common.deploy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "deploy_drain_control")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeployDrainControl {

    @Id
    @Column(name = "control_id")
    private Long id;

    @Column(name = "draining", nullable = false)
    private boolean draining;

    @Column(
            name = "updated_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private LocalDateTime updatedAt;

    public void changeDraining(boolean draining) {
        this.draining = draining;
    }
}
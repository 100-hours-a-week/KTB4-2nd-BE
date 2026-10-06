package com.yeodam.yeodambe.common.deploy.repository;

import com.yeodam.yeodambe.common.deploy.entity.DeployDrainControl;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface DeployDrainControlRepository
        extends JpaRepository<DeployDrainControl, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select control
            from DeployDrainControl control
            where control.id = 1
            """)
    Optional<DeployDrainControl> findForUpdate();
}
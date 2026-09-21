package com.yeodam.yeodambe.user.repository;

import com.yeodam.yeodambe.user.entity.OAuthStateEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OAuthStateRepository
        extends JpaRepository<OAuthStateEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT state
            FROM OAuthStateEntity state
            WHERE state.stateHash = :stateHash
            """)
    Optional<OAuthStateEntity> findByStateHashForUpdate(
            @Param("stateHash") String stateHash
    );

    long deleteByExpiresAtLessThanEqual(LocalDateTime now);
}
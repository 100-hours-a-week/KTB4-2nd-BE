package com.yeodam.yeodambe.user.repository;

import com.yeodam.yeodambe.user.entity.LoginSessionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface LoginSessionRepository
        extends JpaRepository<LoginSessionEntity, Long> {

    Optional<LoginSessionEntity> findBySid(String sid);

    Optional<LoginSessionEntity> findByRefreshTokenHash(
            String refreshTokenHash
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT session
            FROM LoginSessionEntity session
            WHERE session.refreshTokenHash = :refreshTokenHash
            """)
    Optional<LoginSessionEntity> findByRefreshTokenHashForUpdate(
            @Param("refreshTokenHash") String refreshTokenHash
    );

    long deleteByUser_UserId(Long userId);

    long deleteByExpiresAtLessThanEqual(LocalDateTime now);

    void deleteBySid(String sid);
}
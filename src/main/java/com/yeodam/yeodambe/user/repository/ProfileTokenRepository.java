package com.yeodam.yeodambe.user.repository;

import com.yeodam.yeodambe.user.entity.ProfileTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ProfileTokenRepository
        extends JpaRepository<ProfileTokenEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT token
            FROM ProfileTokenEntity token
            WHERE token.tokenHash = :tokenHash
            """)
    Optional<ProfileTokenEntity> findByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash
    );

    long deleteByExpiresAtLessThanEqual(LocalDateTime now);
}
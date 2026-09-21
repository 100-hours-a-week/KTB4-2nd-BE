package com.yeodam.yeodambe.user.repository;

import com.yeodam.yeodambe.user.entity.CsrfTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface CsrfTokenRepository
        extends JpaRepository<CsrfTokenEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT token
            FROM CsrfTokenEntity token
            WHERE token.browserContextHash = :browserContextHash
            """)
    Optional<CsrfTokenEntity> findByBrowserContextHashForUpdate(
            @Param("browserContextHash") String browserContextHash
    );

    Optional<CsrfTokenEntity> findByBrowserContextHash(
            String browserContextHash
    );

    void deleteByBrowserContextHash(String browserContextHash);

    long deleteByExpiresAtLessThanEqual(LocalDateTime now);
}
package com.yeodam.yeodambe.user.repository;

import com.yeodam.yeodambe.user.entity.UserStats;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface UserStatsRepository extends JpaRepository<UserStats, Long> {

    @Query("""
            select stats.user.userId from UserStats stats
            where stats.user.userId > :afterUserId
              and stats.deletedAt is null
              and stats.user.deletedAt is null
            order by stats.user.userId
            """)
    List<Long> findActiveUserIdsAfter(Long afterUserId, Pageable pageable);

    Optional<UserStats> findByUser_UserId(Long userId);

    Optional<UserStats> findByUser_UserIdAndDeletedAtIsNull(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select stats from UserStats stats
            where stats.user.userId = :userId and stats.deletedAt is null
            """)
    Optional<UserStats> findActiveByUserIdForUpdate(Long userId);
}

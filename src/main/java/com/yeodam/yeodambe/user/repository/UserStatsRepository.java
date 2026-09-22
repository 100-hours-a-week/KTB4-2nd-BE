package com.yeodam.yeodambe.user.repository;

import com.yeodam.yeodambe.user.entity.UserStats;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserStatsRepository extends JpaRepository<UserStats, Long> {

    Optional<UserStats> findByUser_UserId(Long userId);

    Optional<UserStats> findByUser_UserIdAndDeletedAtIsNull(Long userId);
}

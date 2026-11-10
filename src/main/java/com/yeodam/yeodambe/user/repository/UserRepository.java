package com.yeodam.yeodambe.user.repository;

import com.yeodam.yeodambe.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.userId = :userId and user.deletedAt is null")
    Optional<User> findActiveForUpdate(@Param("userId") Long userId);

    Optional<User> findByUserIdAndDeletedAtIsNull(Long userId);

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    boolean existsByEmailAndDeletedAtIsNull(String email);
}

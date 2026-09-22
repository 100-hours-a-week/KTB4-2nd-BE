package com.yeodam.yeodambe.user.repository;

import com.yeodam.yeodambe.user.entity.Consent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConsentRepository extends JpaRepository<Consent, Long> {

    Optional<Consent> findByUser_UserId(Long userId);

    Optional<Consent> findByUser_UserIdAndDeletedAtIsNull(Long userId);
}

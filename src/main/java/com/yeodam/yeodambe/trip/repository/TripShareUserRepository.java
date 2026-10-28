package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripShareUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TripShareUserRepository extends JpaRepository<TripShareUser, Long> {
    Optional<TripShareUser> findByTripIdAndGranteeUserId(Long tripId, Long granteeUserId);
}

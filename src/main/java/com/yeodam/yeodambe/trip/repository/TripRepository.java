package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<Trip, Long> {
    boolean existsByUserIdAndTripNameAndDeletedAtIsNull(Long userId, String tripName);
}

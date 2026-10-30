package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripEditCommandRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TripEditCommandRecordRepository extends JpaRepository<TripEditCommandRecord, Long> {
    Optional<TripEditCommandRecord> findByUserIdAndRequestId(Long userId, String requestId);
}

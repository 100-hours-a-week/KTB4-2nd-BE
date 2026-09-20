package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {
    boolean existsByUserIdAndTripNameAndDeletedAtIsNull(Long userId, String tripName);

    boolean existsByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    Optional<Trip> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    @Modifying
    @Query("""
        update Trip t set t.processingStatus = :processing
        where t.id = :tripId and t.userId = :userId and t.deletedAt is null
          and (t.processingStatus = :processing or t.processingStatus = :failed)
    """)
    int prepareInitialUpload(Long tripId, Long userId, ProcessingStatus processing, ProcessingStatus failed);

    @Modifying
    @Query("""
        update Trip t set t.processingStatus = :status
        where t.id = :tripId and t.userId = :userId and t.deletedAt is null
          and t.processingStatus = :processing
    """)
    int finishInitialUpload(Long tripId, Long userId, ProcessingStatus processing,
                            ProcessingStatus status);


}

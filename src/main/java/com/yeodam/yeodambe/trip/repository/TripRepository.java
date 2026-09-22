package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.time.LocalDateTime;

public interface TripRepository extends JpaRepository<Trip, Long> {
    boolean existsByUserIdAndTripNameAndDeletedAtIsNull(Long userId, String tripName);

    boolean existsByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    boolean existsByIdAndUserIdAndDeletedAtIsNullAndProcessingStatus(
            Long id, Long userId, ProcessingStatus processingStatus);

    Optional<Trip> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    Optional<Trip> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select t from Trip t
        where t.id = :tripId and t.userId = :userId and t.deletedAt is null
          and t.processingStatus = :processing
    """)
    Optional<Trip> findProcessableForUpdate(
            Long tripId, Long userId, ProcessingStatus processing);

    @Modifying(clearAutomatically = true)
    @Query("""
        update Trip t set t.processingStatus = :canceled, t.deletedAt = :deletedAt
        where t.id = :tripId and t.userId = :userId and t.deletedAt is null
          and t.processingStatus = :processing
    """)
    int cancelProcessing(Long tripId, Long userId, ProcessingStatus processing,
                         ProcessingStatus canceled, LocalDateTime deletedAt);

    @Modifying
    @Query("""
        update Trip t set t.processingStatus = :processing
        where t.id = :tripId and t.userId = :userId and t.deletedAt is null
          and (t.processingStatus = :processing or t.processingStatus = :failed)
    """)
    int prepareInitialUpload(Long tripId, Long userId, ProcessingStatus processing, ProcessingStatus failed);

    @Modifying(clearAutomatically = true)
    @Query("""
        update Trip t set t.processingStatus = :status
        where t.id = :tripId and t.userId = :userId and t.deletedAt is null
          and t.processingStatus = :processing
    """)
    int finishInitialUpload(Long tripId, Long userId, ProcessingStatus processing,
                            ProcessingStatus status);


}

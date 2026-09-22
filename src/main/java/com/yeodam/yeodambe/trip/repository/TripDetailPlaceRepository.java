package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;

public interface TripDetailPlaceRepository extends JpaRepository<TripDetailPlace, Long> {
    long countByTripIdAndDeletedAtIsNull(Long tripId);

    @Modifying
    @Query("""
            update TripDetailPlace place set place.deletedAt = :deletedAt
            where place.tripId = :tripId and place.deletedAt is null
            """)
    int softDeleteByTripId(Long tripId, LocalDateTime deletedAt);

    @Modifying
    @Query("""
            update TripDetailPlace place
            set place.deletedAt = :deletedAt
            where place.tripId in (
                select trip.id
                from Trip trip
                where trip.userId = :userId
            )
              and place.deletedAt is null
            """)
    int softDeleteByUserId(Long userId, LocalDateTime deletedAt);

    boolean existsByIdAndTripIdAndDeletedAtIsNull(
            Long id,
            Long tripId
    );
}

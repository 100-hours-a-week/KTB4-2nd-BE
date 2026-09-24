package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TripDetailPlaceRepository extends JpaRepository<TripDetailPlace, Long> {
    @Query("""
            select place
            from TripDetailPlace place
            where place.tripId = :tripId
              and place.deletedAt is null
              and (
                    :cursorPlaceName is null
                    or place.placeName > :cursorPlaceName
                    or (place.placeName = :cursorPlaceName and place.id > :cursorTripPlaceId)
              )
            order by place.placeName asc, place.id asc
            """)
    List<TripDetailPlace> findPlaceFoldersWithCursor(
            @Param("tripId") Long tripId,
            @Param("cursorPlaceName") String cursorPlaceName,
            @Param("cursorTripPlaceId") Long cursorTripPlaceId,
            Pageable pageable
    );

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

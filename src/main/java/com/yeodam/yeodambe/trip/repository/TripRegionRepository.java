package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;
import java.time.LocalDateTime;

public interface TripRegionRepository extends JpaRepository<TripRegion, Long> {
    @Query("""
            SELECT new com.yeodam.yeodambe.trip.repository.TripRegionName(
                region.trip.id, region.regionName
            )
            FROM TripRegion region
            WHERE region.trip.id IN :tripIds
              AND region.deletedAt IS NULL
            ORDER BY region.trip.id ASC, region.id ASC
            """)
    List<TripRegionName> findNamesByTripIds(@Param("tripIds") Collection<Long> tripIds);

    @Query("""
            SELECT region
            FROM TripRegion region
            JOIN FETCH region.trip trip
            WHERE trip.userId = :userId
              AND trip.processingStatus = :status
              AND trip.deletedAt IS NULL
              AND region.deletedAt IS NULL
            ORDER BY region.regionCode ASC,
                     trip.createdAt ASC,
                     trip.id ASC
            """)
    List<TripRegion> findAllForMap(
            @Param("userId") Long userId,
            @Param("status") ProcessingStatus status
    );

    List<TripRegion> findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(Long tripId);

    @Modifying
    @Query("""
            update TripRegion region set region.deletedAt = :deletedAt
            where region.trip.id = :tripId and region.deletedAt is null
            """)
    int softDeleteByTripId(Long tripId, LocalDateTime deletedAt);
}

package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TripRegionRepository extends JpaRepository<TripRegion, Long> {

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
}
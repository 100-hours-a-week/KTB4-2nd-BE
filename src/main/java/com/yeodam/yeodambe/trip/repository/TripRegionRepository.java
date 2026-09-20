package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripRegion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripRegionRepository extends JpaRepository<TripRegion, Long> {
    List<TripRegion> findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(Long tripId);
}

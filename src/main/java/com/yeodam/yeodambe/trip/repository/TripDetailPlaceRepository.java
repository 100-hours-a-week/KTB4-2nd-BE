package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripDetailPlaceRepository extends JpaRepository<TripDetailPlace, Long> {
    long countByTripIdAndDeletedAtIsNull(Long tripId);
}

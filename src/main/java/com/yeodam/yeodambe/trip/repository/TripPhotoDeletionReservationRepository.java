package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripPhotoDeletionReservation;
import com.yeodam.yeodambe.trip.entity.TripPhotoDeletionReservation.Status;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripPhotoDeletionReservationRepository extends JpaRepository<TripPhotoDeletionReservation, Long> {
    List<TripPhotoDeletionReservation> findAllByTripIdAndStatusOrderById(Long tripId, Status status);
}

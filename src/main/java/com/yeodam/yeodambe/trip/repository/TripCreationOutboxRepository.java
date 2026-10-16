package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripCreationOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripCreationOutboxRepository extends JpaRepository<TripCreationOutbox, String> {
}

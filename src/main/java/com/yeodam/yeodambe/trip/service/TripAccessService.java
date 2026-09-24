package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripAccessService {
    private final TripRepository tripRepository;

    public Trip requireReadableTrip(Long tripId, Long userId) {
        return tripRepository.findByIdAndUserIdAndDeletedAtIsNull(tripId, userId)
                .orElseThrow(TripNotFoundException::new);
    }
}

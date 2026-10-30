package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripEditAccess;
import com.yeodam.yeodambe.trip.service.response.TripWebSocketAccessResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TripEditAccessService {
    private final TripRepository trips;
    private final TripEditAuthorizationService authorization;

    @Transactional(readOnly = true)
    public TripWebSocketAccessResponse findAccess(Long tripId, Long actorId) {
        Trip trip = trips.findByIdAndDeletedAtIsNull(tripId).orElseThrow(TripNotFoundException::new);
        TripEditAccess access = authorization.requireReadPermission(trip, actorId);
        return new TripWebSocketAccessResponse(tripId, actorId, access.permission(),
                access.checkedAt(), access.accessValidUntil());
    }
}

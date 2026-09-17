package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;

public record TripCreateResponse(
        Long tripId,
        ProcessingStatus status
) {
}

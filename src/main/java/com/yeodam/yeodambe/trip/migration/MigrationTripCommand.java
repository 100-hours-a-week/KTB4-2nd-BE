package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;

import java.time.LocalDate;
import java.util.List;

public record MigrationTripCommand(
        String requestId,
        Long userId,
        String payloadHash,
        String tripName,
        LocalDate startDate,
        LocalDate endDate,
        List<String> regionCodes
) {
    public TripCreateRequest tripRequest() {
        return new TripCreateRequest(tripName, startDate, endDate, regionCodes);
    }
}

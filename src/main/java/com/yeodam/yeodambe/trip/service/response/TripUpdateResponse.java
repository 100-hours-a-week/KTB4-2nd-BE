package com.yeodam.yeodambe.trip.service.response;

import java.time.LocalDate;
import java.util.List;

public record TripUpdateResponse(
        Long tripId,
        String tripName,
        LocalDate startDate,
        LocalDate endDate,
        List<Region> regions
) {
    public record Region(
            Long regionId,
            String regionCode,
            String regionName
    ) {
    }
}
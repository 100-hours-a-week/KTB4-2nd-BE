package com.yeodam.yeodambe.trip.service.response;

import java.time.LocalDate;
import java.util.List;

public record TripEditResponse(
        Long tripId,
        String tripName,
        LocalDate startDate,
        LocalDate endDate,
        List<Region> regions,
        long attachmentCount,
        TripAttachmentListResponse attachments
) {
    public record Region(
            Long regionId,
            String regionCode,
            String regionName
    ) {
    }
}

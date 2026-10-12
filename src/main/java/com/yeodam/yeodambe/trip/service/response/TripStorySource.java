package com.yeodam.yeodambe.trip.service.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record TripStorySource(
        Long tripId,
        String tripName,
        LocalDate startDate,
        LocalDate endDate,
        List<Place> places
) {
    public record Place(
            Long tripPlaceId,
            String placeName,
            List<Photo> photos
    ) {
    }

    public record Photo(
            Long tripAttachmentId,
            String analyzeStorageKey,
            LocalDateTime takenAt,
            Integer evaluation
    ) {
    }
}

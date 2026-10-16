package com.yeodam.yeodambe.trip.service.response;

import java.time.LocalDateTime;

public record TripSearchCandidateResponse(
        Long tripAttachmentId,
        String analyzeStorageKey,
        LocalDateTime takenAt,
        String placeName,
        String tripName
) {
}

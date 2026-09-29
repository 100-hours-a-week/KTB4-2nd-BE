package com.yeodam.yeodambe.trip.service.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record TripDraftResponse(Long draftId, String tripName, List<String> regionCodes,
                                LocalDate startDate, LocalDate endDate, Long submittedTripId,
                                LocalDateTime updatedAt) {
}

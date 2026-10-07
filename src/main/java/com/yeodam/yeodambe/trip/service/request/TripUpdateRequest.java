package com.yeodam.yeodambe.trip.service.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record TripUpdateRequest(
        String tripName,
        LocalDate startDate,
        LocalDate endDate,

        @Size(min = 1, max = 10)
        List<@NotNull @Pattern(regexp = "\\d{5}") String> regionCodes
) {
    @AssertTrue
    public boolean isAnyFieldPresent() {
        return tripName != null
                || startDate != null
                || endDate != null
                || regionCodes != null;
    }

    @AssertTrue
    public boolean isTripNameValid() {
        return tripName == null
                || (!tripName.isBlank()
                && tripName.codePointCount(0, tripName.length()) <= 10);
    }
}
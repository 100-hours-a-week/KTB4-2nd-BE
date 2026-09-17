package com.yeodam.yeodambe.trip.service.request;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

public record TripCreateRequest(
        @NotBlank
        String tripName,
        @NotNull
        LocalDate startDate,
        @NotNull
        LocalDate endDate,
        @NotNull @Size(min = 1, max = 10)
        List<@NotNull @Pattern(regexp = "\\d{5}") String> regionCodes
        ) {
    @AssertTrue
    public boolean isTripNameLengthValid() {
        return tripName == null || tripName.codePointCount(0, tripName.length()) <= 10;
    }
}

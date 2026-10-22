package com.yeodam.yeodambe.trip.service.request;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

public record TripCreateRequest(
        @NotBlank
        @Pattern(regexp = "^[^\\p{Cc}]+$")
        String tripName,
        @NotNull
        LocalDate startDate,
        @NotNull
        LocalDate endDate,
        @NotNull @Size(min = 1, max = 10)
        List<@NotNull @Pattern(regexp = "\\d{5}") String> regionCodes
) {
    public TripCreateRequest {
        // 제어문자는 strip으로 제거하지 않고 입력 검증에서 거부한다.
        if (tripName != null && tripName.codePoints().noneMatch(Character::isISOControl)) {
            tripName = tripName.strip();
        }
    }

    @AssertTrue
    public boolean isTripNameLengthValid() {
        return tripName == null || tripName.codePointCount(0, tripName.length()) <= 10;
    }
}

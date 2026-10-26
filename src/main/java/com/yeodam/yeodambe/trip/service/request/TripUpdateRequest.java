package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.validation.TextValidation;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record TripUpdateRequest(
        @Pattern(regexp = "^[^\\p{Cc}]+$")
        String tripName,
        LocalDate startDate,
        LocalDate endDate,

        @Size(min = 1, max = 10)
        List<@NotNull @Pattern(regexp = "\\d{5}") String> regionCodes
) {
    public TripUpdateRequest {
        // 제어문자는 strip으로 제거하지 않고 입력 검증에서 거부한다.
        if (tripName != null && tripName.codePoints().noneMatch(Character::isISOControl)) {
            tripName = tripName.strip();
        }
    }

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
                || (TextValidation.hasVisibleContent(tripName)
                && tripName.codePointCount(0, tripName.length()) <= 10);
    }
}

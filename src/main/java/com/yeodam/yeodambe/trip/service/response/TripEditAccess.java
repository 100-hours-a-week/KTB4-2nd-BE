package com.yeodam.yeodambe.trip.service.response;

import java.time.Instant;

public record TripEditAccess(
        String permission,
        Instant checkedAt,
        Instant accessValidUntil
) {
}

package com.yeodam.yeodambe.trip.service.response;

import java.time.Instant;

public record TripWebSocketAccessResponse(
        Long tripId,
        Long userId,
        String permission,
        Instant checkedAt,
        Instant accessValidUntil
) {
}

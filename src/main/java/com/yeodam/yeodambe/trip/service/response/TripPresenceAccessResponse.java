package com.yeodam.yeodambe.trip.service.response;

import java.time.Instant;

public record TripPresenceAccessResponse(
        Long tripId,
        Long userId,
        String nickname,
        String profileImageUrl,
        String permission,
        Instant checkedAt,
        Instant accessValidUntil,
        Instant shareExpiresAt
) {
}

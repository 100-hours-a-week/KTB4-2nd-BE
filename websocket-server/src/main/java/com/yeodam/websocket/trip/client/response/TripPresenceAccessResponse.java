package com.yeodam.websocket.trip.client.response;

import java.time.Instant;

public record TripPresenceAccessResponse(
        String message,
        AccessData data
) {
    public record AccessData(
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
}

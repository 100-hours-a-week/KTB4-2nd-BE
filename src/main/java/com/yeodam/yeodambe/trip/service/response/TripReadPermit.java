package com.yeodam.yeodambe.trip.service.response;

import java.time.Instant;

public record TripReadPermit(
        Long userId,
        Long tripId,
        String snapshotId,
        long validUntilNanos,
        Instant shareExpiresAt
) {

    public boolean isUsableAt(long nowNanos, Instant now) {
        return validUntilNanos - nowNanos > 0L && (shareExpiresAt == null || shareExpiresAt.isAfter(now));
    }
}

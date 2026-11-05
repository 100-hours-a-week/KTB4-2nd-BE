package com.yeodam.yeodambe.trip.service.response;

public record CachedTripReadAccessSnapshot(
        TripReadAccessSnapshot snapshot,
        long validUntilNanos,
        String snapshotId
) {

    public boolean isUsableAt(long nowNanos) {
        return validUntilNanos - nowNanos > 0L;
    }
}

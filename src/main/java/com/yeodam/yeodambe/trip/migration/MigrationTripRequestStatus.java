package com.yeodam.yeodambe.trip.migration;

public record MigrationTripRequestStatus(
        String requestId,
        String status,
        Long tripId,
        String errorCode
) {
    public static MigrationTripRequestStatus pending(String requestId) {
        return new MigrationTripRequestStatus(requestId, "PENDING", null, null);
    }
}

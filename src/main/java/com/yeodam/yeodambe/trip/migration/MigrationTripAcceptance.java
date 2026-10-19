package com.yeodam.yeodambe.trip.migration;

public record MigrationTripAcceptance(String requestId, String status) {
    public MigrationTripAcceptance(String requestId) {
        this(requestId, "ACCEPTED");
    }
}

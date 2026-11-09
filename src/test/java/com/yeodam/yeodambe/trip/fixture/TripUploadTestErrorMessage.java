package com.yeodam.yeodambe.trip.fixture;

public enum TripUploadTestErrorMessage {
    PRESIGN_FAILURE("test presign failure"),
    ANALYSIS_OUTBOX_FAILURE("analysis outbox persistence failed"),
    DATABASE_UNAVAILABLE("database unavailable"),
    NOTIFICATION_PUBLISH_FAILURE("redis unavailable");

    private final String message;

    TripUploadTestErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}

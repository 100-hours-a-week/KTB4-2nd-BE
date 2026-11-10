package com.yeodam.yeodambe.trip.fixture;

public enum TripUploadTestErrorMessage {
    PRESIGN_FAILURE("test presign failure"),
    ANALYSIS_OUTBOX_FAILURE("analysis outbox persistence failed"),
    NOTIFICATION_OUTBOX_FAILURE("notification outbox persistence failed"),
    DATABASE_UNAVAILABLE("database unavailable"),
    DATABASE_WRITE_CONSTRAINT_FAILED("database write constraint failed"),
    S3_FAILURE("S3 failure"),
    NOTIFICATION_PUBLISH_FAILURE("redis unavailable");

    private final String message;

    TripUploadTestErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}

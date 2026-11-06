package com.yeodam.yeodambe.trip.service;

public enum TripEditLockTestErrorMessage {
    REDIS_ACCESS_FORBIDDEN("Redis must not be contacted"),
    BUSINESS_EXECUTION_FORBIDDEN("work must not run"),
    REJECT_BEFORE_REDIS("must reject before Redis"),
    SENSITIVE_REDIS_CONNECTION("redis://secret@example"),
    SENSITIVE_CLIENT_INITIALIZATION("secret redis URL"),
    INTERRUPTED("test"),
    SENSITIVE_RELEASE_FAILURE("secret"),
    BUSINESS_ROLLBACK("business rollback"),
    BUSINESS_ENTERED("business entered"),
    REJECTION_PERSISTED("rejection persisted"),
    ROLLBACK("rollback"),
    TRANSPORT_WRITE_FAILED("isolated Redis transport write failed"),
    OWNERSHIP_LOST("lock ownership lost"),
    OWNER_RELEASE_TIMED_OUT("owner release timed out");

    private final String message;

    TripEditLockTestErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}

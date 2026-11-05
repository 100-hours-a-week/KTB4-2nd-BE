package com.yeodam.yeodambe.trip.fixture;

public enum TripAttachmentCacheErrorMessage {
    STORE_UNAVAILABLE("unavailable"),
    HIT_MUST_REUSE_RESPONSE("hit must reuse URL response"),
    DATABASE_FAILED("database failed"),
    CLEANUP_FAILED("cleanup failed"),
    SIGNING_FAILED("signing failed"),
    OBSERVER_FAILED("observer failed"),
    COMMAND_QUEUE_FAILED("command queue failed");

    private final String message;

    TripAttachmentCacheErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}

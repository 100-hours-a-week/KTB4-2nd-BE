package com.yeodam.yeodambe.trip.service;

public record TripAttachmentReadEvent(
        Phase phase,
        Outcome outcome,
        long elapsedNanos,
        int active,
        String refreshId
) {

    public enum Phase {
        ATTEMPT,
        ROLE,
        EXECUTION,
        FRESHNESS,
        RETURN,
        REJECTION
    }

    public enum Outcome {
        HIT,
        MISS,
        REJOINED,
        PUBLISHED,
        ACQUIRED,
        BUSY,
        TIMEOUT,
        INTERRUPTED,
        OWNER_LOST,
        STORE_UNAVAILABLE,
        LEADER,
        FOLLOWER,
        SUCCESS,
        EXPIRED,
        RETRY_EXHAUSTED
    }
}

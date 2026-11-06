package com.yeodam.yeodambe.trip.exception;

public class TripEditLockRejectedException extends RuntimeException {
    private final Reason reason;

    public TripEditLockRejectedException(Reason reason) {
        super(TripInternalErrorMessage.EDIT_LOCK_REJECTED.message());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        CONTENDED,
        STORE_UNAVAILABLE,
        INTERRUPTED
    }
}

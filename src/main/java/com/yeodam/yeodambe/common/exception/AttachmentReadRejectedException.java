package com.yeodam.yeodambe.common.exception;

public class AttachmentReadRejectedException extends AttachmentListUnavailableException {

    private final Reason reason;

    public AttachmentReadRejectedException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        TIMEOUT,
        INTERRUPTED,
        OWNER_LOST,
        STORE_UNAVAILABLE,
        RETRY_EXHAUSTED
    }
}

package com.yeodam.yeodambe.common.exception;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;

public class AttachmentListUnavailableException extends RuntimeException {

    public AttachmentListUnavailableException() {
        super(TripInternalErrorMessage.PHOTO_LIST_STATE_UNAVAILABLE.message());
    }

    public AttachmentListUnavailableException(Throwable cause) {
        super(TripInternalErrorMessage.PHOTO_LIST_STATE_UNAVAILABLE.message(), cause);
    }
}

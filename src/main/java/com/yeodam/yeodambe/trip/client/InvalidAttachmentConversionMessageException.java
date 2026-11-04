package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;

public class InvalidAttachmentConversionMessageException extends RuntimeException {
    public InvalidAttachmentConversionMessageException() {
        super(TripInternalErrorMessage.CONVERSION_MESSAGE_ROUTE_INVALID.message());
    }
}

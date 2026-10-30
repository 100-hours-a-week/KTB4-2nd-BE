package com.yeodam.websocket.trip.exception;

import com.yeodam.websocket.common.response.ErrorMessage;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public class TripPresenceException extends RuntimeException {
    private final ErrorMessage errorMessage;

    public TripPresenceException(ErrorMessage message) {
        super(message.message());
        this.errorMessage = message;
    }
}

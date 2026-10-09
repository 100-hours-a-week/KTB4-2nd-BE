package com.yeodam.yeodambe.common.event;

import org.springframework.util.Assert;

public interface EventHandler<T extends EventPayload> {

    EventType eventType();

    void handle(T event);

    default boolean supports(EventType type) {
        return eventType() == type;
    }

    default void handleIfSupported(T event) {
        Assert.notNull(event, EventInternalErrorMessage.EVENT_PAYLOAD_MISSING.message());
        if (supports(event.eventType())) {
            handle(event);
        }
    }
}

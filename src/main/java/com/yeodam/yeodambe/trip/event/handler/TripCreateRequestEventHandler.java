package com.yeodam.yeodambe.trip.event.handler;

import com.yeodam.yeodambe.common.event.EventHandler;
import com.yeodam.yeodambe.common.event.EventInternalErrorMessage;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.trip.event.TripCreateRequestEvent;
import com.yeodam.yeodambe.trip.service.InitialAttachmentUploadTransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
@RequiredArgsConstructor
public class TripCreateRequestEventHandler implements EventHandler<TripCreateRequestEvent> {
    private final InitialAttachmentUploadTransactionService transactions;

    @Override
    public EventType eventType() {
        return EventType.TRIP_CREATE_REQUEST;
    }

    @Override
    public void handle(TripCreateRequestEvent event) {
        Assert.notNull(event, EventInternalErrorMessage.EVENT_PAYLOAD_MISSING.message());
        if (!supports(event.eventType())) {
            return;
        }
        transactions.enqueueRequest(event);
    }
}

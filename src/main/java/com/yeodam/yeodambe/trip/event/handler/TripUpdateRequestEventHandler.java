package com.yeodam.yeodambe.trip.event.handler;

import com.yeodam.yeodambe.common.event.EventHandler;
import com.yeodam.yeodambe.common.event.EventInternalErrorMessage;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.trip.event.TripUpdateRequestEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
public class TripUpdateRequestEventHandler implements EventHandler<TripUpdateRequestEvent> {

    @Override
    public EventType eventType() {
        return EventType.TRIP_UPDATE_REQUEST;
    }

    @Override
    public void handle(TripUpdateRequestEvent event) {
        Assert.notNull(event, EventInternalErrorMessage.EVENT_PAYLOAD_MISSING.message());
        if (!supports(event.eventType())) {
            return;
        }

        // 미구현 상태에서 정상 반환하면 소비자가 처리 완료로 판단할 수 있다.
        throw new UnsupportedOperationException(
                EventInternalErrorMessage.EVENT_HANDLER_NOT_IMPLEMENTED.message().formatted(eventType()));
    }
}

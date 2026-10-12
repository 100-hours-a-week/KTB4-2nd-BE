package com.yeodam.yeodambe.trip.event;

import com.yeodam.yeodambe.common.event.EventPayload;
import com.yeodam.yeodambe.common.event.EventType;

/**
 * 여행 수정 AI 결과 이벤트. 메시지 계약 확정 후 필드를 정의한다.
 */
public class TripUpdateResultEvent implements EventPayload {

    @Override
    public EventType eventType() {
        return EventType.TRIP_UPDATE_RESULT;
    }
}

package com.yeodam.yeodambe.story.event;

import com.yeodam.yeodambe.common.event.EventPayload;
import com.yeodam.yeodambe.common.event.EventType;

/**
 * 스토리 생성 AI 요청 이벤트. 메시지 계약 확정 후 필드를 정의한다.
 */
public class StoryCreateRequestEvent implements EventPayload {

    @Override
    public EventType eventType() {
        return EventType.STORY_CREATE_REQUEST;
    }
}

package com.yeodam.yeodambe.common.event;

import com.fasterxml.jackson.annotation.JsonIgnore;

public interface EventPayload {

    // 내부 라우팅용 타입은 AI 메시지의 type 필드와 별도로 관리한다.
    @JsonIgnore
    EventType eventType();
}

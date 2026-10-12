package com.yeodam.yeodambe.common.event;

public enum EventInternalErrorMessage {
    EVENT_PAYLOAD_MISSING("이벤트 페이로드가 없습니다."),
    EVENT_JSON_BLANK("이벤트 JSON이 비어 있습니다."),
    EVENT_PAYLOAD_TYPE_MISSING("이벤트 페이로드 타입이 없습니다."),
    EVENT_JSON_NULL_PAYLOAD("이벤트 JSON의 페이로드가 null입니다."),
    EVENT_TYPE_MISSING("이벤트 타입이 없습니다."),
    KAFKA_TOPIC_NOT_CONFIGURED("이벤트 토픽이 설정되지 않았거나 비어 있습니다: %s"),
    EVENT_HANDLER_NOT_IMPLEMENTED("이벤트 핸들러가 구현되지 않았습니다: %s");

    private final String message;

    EventInternalErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}

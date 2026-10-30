package com.yeodam.websocket.common.websocket.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum WebSocketEventFailure {
    EVENT_TOO_LARGE("웹소켓 이벤트 크기가 상한을 초과했습니다."),
    INVALID_JSON("웹소켓 이벤트의 JSON 형식이 올바르지 않습니다."),
    EVENT_MISSING("웹소켓 이벤트가 없습니다."),
    EVENT_ID_MISSING("웹소켓 이벤트 ID가 없습니다."),
    EVENT_TYPE_MISSING("웹소켓 이벤트 종류가 없습니다."),
    SCHEMA_VERSION_UNSUPPORTED("지원하지 않는 웹소켓 이벤트 스키마 버전입니다."),
    OCCURRED_AT_MISSING("웹소켓 이벤트 발생 시각이 없습니다."),
    TARGET_MISSING("웹소켓 이벤트 수신 대상이 없습니다."),
    TARGET_TYPE_MISSING("웹소켓 이벤트 수신 대상의 종류가 없습니다."),
    TARGET_ID_INVALID("웹소켓 이벤트 수신 대상 ID가 올바르지 않습니다."),
    TARGET_TYPE_MISMATCH("웹소켓 이벤트 종류와 수신 대상의 종류가 일치하지 않습니다."),
    PAYLOAD_MISSING("웹소켓 이벤트 본문이 없습니다."),
    PAYLOAD_FORMAT_INVALID("웹소켓 이벤트 본문 형식이 올바르지 않습니다."),
    PAYLOAD_MESSAGE_INVALID("웹소켓 이벤트 본문의 메시지가 올바르지 않습니다."),
    PAYLOAD_DATA_MISSING("웹소켓 이벤트 본문의 데이터가 없습니다."),
    DELIVERY_FAILED("웹소켓 이벤트를 전달하지 못했습니다.");

    private final String message;
}

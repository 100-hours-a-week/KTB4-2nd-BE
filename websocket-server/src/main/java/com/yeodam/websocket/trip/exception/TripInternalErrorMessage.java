package com.yeodam.websocket.trip.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum TripInternalErrorMessage {
    PRESENCE_NAMESPACE_MISSING("여행 참여 상태를 저장할 네임스페이스가 필요합니다."),
    PRESENCE_PARTITION_COUNT_INVALID("여행 참여 상태의 파티션 수가 올바르지 않습니다."),
    PRESENCE_TRIP_CONNECTION_LIMIT_INVALID("여행별 참여 연결 상한은 1~100개여야 합니다."),
    PRESENCE_CONNECTION_TRIP_LIMIT_INVALID("연결별 참여 여행 수 상한이 올바르지 않습니다.");

    private final String message;
}

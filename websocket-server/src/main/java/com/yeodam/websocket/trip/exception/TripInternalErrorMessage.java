package com.yeodam.websocket.trip.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum TripInternalErrorMessage {
    ACCESS_RESPONSE_INVALID("여행 접근 권한 응답이 올바르지 않습니다."),
    ACCESS_RESPONSE_EXPIRED("여행 접근 권한 응답의 유효기간이 지났습니다."),
    ACCESS_TIME_INVALID("여행 접근 권한 확인 시각이 올바르지 않습니다."),
    COMMAND_RESULT_UNKNOWN("알 수 없는 명령 처리 결과입니다."),
    BACKEND_CREDENTIALS_MISSING("백엔드 인증 정보가 없습니다."),
    PRESENCE_NAMESPACE_MISSING("여행 참여 상태를 저장할 네임스페이스가 필요합니다."),
    PRESENCE_PARTITION_COUNT_INVALID("여행 참여 상태의 파티션 수가 올바르지 않습니다."),
    PRESENCE_TRIP_CONNECTION_LIMIT_INVALID("여행별 참여 연결 상한은 1~100개여야 합니다."),
    PRESENCE_CONNECTION_TRIP_LIMIT_INVALID("연결별 참여 여행 수 상한이 올바르지 않습니다.");

    private final String message;
}

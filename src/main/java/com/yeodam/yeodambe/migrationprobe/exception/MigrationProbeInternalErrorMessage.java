package com.yeodam.yeodambe.migrationprobe.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum MigrationProbeInternalErrorMessage {
    STORE_UNAVAILABLE("마이그레이션 시험 저장소 처리에 실패했습니다."),
    STORED_RESULT_MISSING("시험 요청의 저장 결과를 확인할 수 없습니다."),
    DUPLICATE_KEY("시험 요청의 키가 이미 존재합니다.");

    private final String message;
}
